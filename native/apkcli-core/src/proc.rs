//! Commands run to completion with their output captured: what `sh_exec`,
//! `build_run` and `test_run` execute.
//!
//! Driven step by step by the caller rather than by one blocking call, and
//! that is the fix for the worst bug the old version had. It waited for the
//! command's pipes to reach end-of-file — and a pipe reaches EOF only when
//! *every* process holding its write end has closed it. `npm run dev &`,
//! `pg_ctl start` without `-l`, any `cmd &`: each hands the write end to a
//! process that does not exit. The call never returned, the tool call sat at
//! "running" for good, and the worker thread it held was gone with it; two of
//! those and every tool that needed the same pool hung behind them. A timeout
//! did not help either, because it signalled only the shell, and a
//! `sleep 1000` under the shell outlived it.
//!
//! Now:
//!   * The command runs in a session of its own (`setsid`), so it and
//!     everything it starts share one process group, and a timeout or a stop
//!     reaches all of it with one `kill(-pgid)`.
//!   * The caller calls [step] in a loop. Each step waits briefly for output,
//!     returns what arrived, and applies the timeout. Output therefore reaches
//!     the transcript while a build runs, and the caller can be cancelled
//!     between steps — cancelling then [close]s, which kills the group.
//!   * Once the command itself has exited, its pipes get [PIPE_GRACE] and no
//!     more. Whatever still holds them is reported: processes of the
//!     command's own group are stopped (their output had nowhere to go), and a
//!     process that detached into its own session — a daemon — keeps running.
//!   * With `tty` the output goes to a pseudo-terminal instead of pipes. On a
//!     pipe, `npm install`, pip and most build tools print nothing until they
//!     are done — no progress, block-buffered — so a running call showed
//!     nothing at all. On a terminal they print what a person would see.
//!     Everything above holds the same way: the master reads EOF when the
//!     last holder of the slave closes it, as a pipe does.

use crate::error::{CoreError, Result};
use crate::pty::{argv_of, env_of};
use once_cell::sync::Lazy;
use parking_lot::Mutex;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::ffi::CString;
use std::fs::File;
use std::io::Write;
use std::os::unix::io::{FromRawFd, IntoRawFd, RawFd};
use std::os::unix::process::{CommandExt, ExitStatusExt};
use std::process::{Child, Command, ExitStatus, Stdio};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

/// How long the pipes may stay open after the command has exited.
const PIPE_GRACE: Duration = Duration::from_millis(500);
/// Between SIGTERM and SIGKILL when the timeout fires.
const TERM_GRACE: Duration = Duration::from_millis(2_000);
/// After SIGKILL, how long to wait for the leader before giving up on it.
const KILL_WAIT: Duration = Duration::from_millis(2_000);

/// The read end of one output pipe, non-blocking.
struct Stream {
    /// -1 once the write side has closed and this end has been released.
    fd: RawFd,
    /// The start of a UTF-8 sequence split across two reads.
    partial: Vec<u8>,
}

impl Stream {
    fn new(fd: RawFd) -> Self {
        if fd >= 0 {
            unsafe {
                let flags = libc::fcntl(fd, libc::F_GETFL);
                libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK);
            }
        }
        Stream { fd, partial: Vec::new() }
    }

    fn is_open(&self) -> bool {
        self.fd >= 0
    }

    /// Everything readable now, up to about `limit` bytes, as text.
    fn drain(&mut self, limit: usize) -> String {
        if self.fd < 0 {
            return String::new();
        }
        let mut bytes = std::mem::take(&mut self.partial);
        let mut buf = [0u8; 16 * 1024];
        let mut eof = false;
        while bytes.len() < limit {
            let n = unsafe { libc::read(self.fd, buf.as_mut_ptr() as *mut libc::c_void, buf.len()) };
            if n > 0 {
                bytes.extend_from_slice(&buf[..n as usize]);
                continue;
            }
            if n == 0 {
                eof = true;
                break;
            }
            match std::io::Error::last_os_error().raw_os_error().unwrap_or(0) {
                libc::EINTR => continue,
                libc::EAGAIN => break,
                // EIO and the like: nothing more will come.
                _ => {
                    eof = true;
                    break;
                }
            }
        }
        if eof {
            self.close();
        }
        decode(bytes, &mut self.partial, eof)
    }

    fn close(&mut self) {
        if self.fd >= 0 {
            unsafe { libc::close(self.fd) };
            self.fd = -1;
        }
    }
}

/// The valid UTF-8 in `bytes`. A sequence cut off at the end is kept in
/// `partial` for the next read, unless this was the last one; invalid bytes
/// become U+FFFD. Decoding each read on its own turned every "ё" that
/// straddled a read into two replacement characters.
fn decode(bytes: Vec<u8>, partial: &mut Vec<u8>, last: bool) -> String {
    let mut out = String::with_capacity(bytes.len());
    let mut rest: &[u8] = &bytes;
    loop {
        match std::str::from_utf8(rest) {
            Ok(text) => {
                out.push_str(text);
                break;
            }
            Err(e) => {
                let valid = e.valid_up_to();
                // SAFETY: from_utf8 just checked these bytes.
                out.push_str(unsafe { std::str::from_utf8_unchecked(&rest[..valid]) });
                match e.error_len() {
                    Some(len) => {
                        out.push('\u{FFFD}');
                        rest = &rest[valid + len..];
                    }
                    None => {
                        if last {
                            out.push('\u{FFFD}');
                        } else {
                            partial.extend_from_slice(&rest[valid..]);
                        }
                        break;
                    }
                }
            }
        }
    }
    out
}

struct Proc {
    child: Child,
    /// The command's process group, which `setsid` made equal to its pid.
    pgid: libc::pid_t,
    out: Stream,
    err: Stream,
    status: Option<ExitStatus>,
    started: Instant,
    deadline: Option<Instant>,
    term_sent: Option<Instant>,
    kill_sent: bool,
    timed_out: bool,
    exited_at: Option<Instant>,
    /// [step] has reported `done`.
    finished: bool,
    /// Processes of the command's group that outlived it while holding its
    /// output; [close] stops them.
    lingering: Vec<(i32, String)>,
}

impl Proc {
    fn reap(&mut self) -> Option<ExitStatus> {
        if self.status.is_none() {
            if let Ok(Some(status)) = self.child.try_wait() {
                self.status = Some(status);
            }
        }
        self.status
    }

    fn signal_group(&self, signal: libc::c_int) -> bool {
        unsafe { libc::kill(-self.pgid, signal) == 0 }
    }

    /// Wait for the leader, in short steps, for at most `limit`.
    fn wait_leader(&mut self, limit: Duration) -> bool {
        let until = Instant::now() + limit;
        while self.reap().is_none() {
            if Instant::now() >= until {
                return false;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        true
    }
}

impl Drop for Proc {
    fn drop(&mut self) {
        self.out.close();
        self.err.close();
    }
}

static PROCS: Lazy<Mutex<HashMap<i64, Arc<Mutex<Proc>>>>> = Lazy::new(|| Mutex::new(HashMap::new()));
static NEXT_ID: AtomicI64 = AtomicI64::new(1);

pub fn call(op: &str, v: &Value) -> Result<Value> {
    match op {
        "proc.spawn" => spawn(v),
        "proc.step" => step(v),
        "proc.signal" => signal(v),
        "proc.close" => close(v),
        other => Err(CoreError::invalid(format!("unknown proc op '{other}'"))),
    }
}

/// Send a signal to a running command's whole process group: Ctrl-C from
/// the terminal panel (SIGINT), so the command ends the way it would at a
/// keyboard and [step] reports how. Only the usual job-control signals;
/// anything else is refused. A command that has already finished is not
/// an error — there is simply nothing left to signal.
fn signal(v: &Value) -> Result<Value> {
    let id = id_of(v)?;
    let sig = v.get("signal").and_then(Value::as_i64).unwrap_or(libc::SIGINT as i64) as libc::c_int;
    if ![libc::SIGINT, libc::SIGTERM, libc::SIGHUP, libc::SIGQUIT].contains(&sig) {
        return Err(CoreError::invalid(format!("signal {sig} is not allowed")));
    }
    let proc = get(id)?;
    let mut proc = proc.lock();
    let delivered = proc.reap().is_none() && proc.signal_group(sig);
    Ok(json!({ "delivered": delivered }))
}

fn id_of(v: &Value) -> Result<i64> {
    v.get("id")
        .and_then(Value::as_i64)
        .ok_or_else(|| CoreError::invalid("missing 'id'"))
}

fn get(id: i64) -> Result<Arc<Mutex<Proc>>> {
    PROCS
        .lock()
        .get(&id)
        .cloned()
        .ok_or_else(|| CoreError::not_found(format!("process {id}")))
}

/// A pseudo-terminal for a command's output: the master, read here like a
/// pipe, and the slave, which becomes the command's stdout and stderr.
///
/// The slave is opened `O_NOCTTY` and the command gets no `TIOCSCTTY`: a
/// terminal to print on, not one to read from. `isatty(1)` is true, so tools
/// print what they print for a person — progress bars, spinners, colour, a
/// line at a time — but nothing can wait for keyboard input: stdin stays
/// `/dev/null` (or the given text), and opening `/dev/tty` fails, as before.
fn output_terminal(cols: u16, rows: u16) -> Result<(RawFd, File)> {
    let (master, slave_path) = crate::pty::open_ptmx()?;
    crate::pty::set_winsize(master, cols, rows);
    let Ok(c_slave) = CString::new(slave_path.as_os_str().as_encoded_bytes()) else {
        unsafe { libc::close(master) };
        return Err(CoreError::internal("pty slave path contains NUL"));
    };
    let slave = unsafe { libc::open(c_slave.as_ptr(), libc::O_RDWR | libc::O_NOCTTY | libc::O_CLOEXEC) };
    if slave < 0 {
        let e = CoreError::last_errno("open(pty slave)");
        unsafe { libc::close(master) };
        return Err(e);
    }
    // SAFETY: just opened, owned by nothing else.
    Ok((master, unsafe { File::from_raw_fd(slave) }))
}

/// Start a command. Returns `{id, pid}`; drive it with [step], end with [close].
///
/// With `tty: true` its stdout and stderr are one terminal of `cols` x `rows`
/// (see [output_terminal]) instead of two pipes; [step] then returns all of
/// it as `stdout`, in the order it was written and with the terminal's
/// `\r\n` line endings.
pub fn spawn(v: &Value) -> Result<Value> {
    let argv = argv_of(v)?;
    let cwd = v.get("cwd").and_then(Value::as_str).unwrap_or(".");
    let stdin = v.get("stdin").and_then(Value::as_str).unwrap_or("").to_string();
    let timeout = v
        .get("timeoutMs")
        .and_then(Value::as_u64)
        .filter(|ms| *ms > 0)
        .map(Duration::from_millis);
    let tty = v.get("tty").and_then(Value::as_bool).unwrap_or(false);

    let mut cmd = Command::new(&argv[0]);
    cmd.args(&argv[1..])
        .current_dir(cwd)
        .stdin(if stdin.is_empty() { Stdio::null() } else { Stdio::piped() });
    let mut master: RawFd = -1;
    if tty {
        let cols = v.get("cols").and_then(Value::as_u64).unwrap_or(120).clamp(20, 1_000) as u16;
        let rows = v.get("rows").and_then(Value::as_u64).unwrap_or(40).clamp(4, 400) as u16;
        let (m, slave) = output_terminal(cols, rows)?;
        let slave_err = match slave.try_clone() {
            Ok(f) => f,
            Err(e) => {
                unsafe { libc::close(m) };
                return Err(e.into());
            }
        };
        cmd.stdout(Stdio::from(slave)).stderr(Stdio::from(slave_err));
        master = m;
    } else {
        cmd.stdout(Stdio::piped()).stderr(Stdio::piped());
    }
    if v.get("clearEnv").and_then(Value::as_bool).unwrap_or(true) {
        cmd.env_clear();
    }
    for (k, val) in env_of(v) {
        cmd.env(k, val);
    }
    // SAFETY: setsid is async-signal-safe, which is all pre_exec may call.
    // A session of its own also means no controlling terminal: a program
    // that opens /dev/tty to ask a question gets an error instead of waiting
    // for an answer nobody can give.
    unsafe {
        cmd.pre_exec(|| {
            if libc::setsid() < 0 {
                return Err(std::io::Error::last_os_error());
            }
            Ok(())
        });
    }

    let spawned = cmd.spawn();
    // The parent's copies of the terminal's slave side go with `cmd`. From
    // here on only the command, and what it starts, holds it, so the master
    // reads end-of-file (EIO) exactly when a pipe would.
    drop(cmd);
    let mut child = match spawned {
        Ok(child) => child,
        Err(e) => {
            if master >= 0 {
                unsafe { libc::close(master) };
            }
            return Err(CoreError::Invalid(format!(
                "cannot spawn {}: {e}. On targetSdk>=29 the binary must live in \
                 nativeLibraryDir or /system/bin.",
                argv[0]
            )));
        }
    };
    let pid = child.id() as libc::pid_t;
    let (out, err) = if tty {
        (Stream::new(master), Stream::new(-1))
    } else {
        (
            Stream::new(child.stdout.take().map(IntoRawFd::into_raw_fd).unwrap_or(-1)),
            Stream::new(child.stderr.take().map(IntoRawFd::into_raw_fd).unwrap_or(-1)),
        )
    };

    if let Some(mut pipe) = child.stdin.take() {
        // On a thread of its own: a command that never reads its stdin would
        // otherwise block the caller on a full pipe. SIGPIPE is blocked here,
        // so a command that exits early fails the write with EPIPE instead of
        // signalling the whole app.
        let _ = std::thread::Builder::new()
            .name("apkcli-stdin".into())
            .spawn(move || {
                unsafe {
                    let mut set: libc::sigset_t = std::mem::zeroed();
                    libc::sigemptyset(&mut set);
                    libc::sigaddset(&mut set, libc::SIGPIPE);
                    libc::pthread_sigmask(libc::SIG_BLOCK, &set, std::ptr::null_mut());
                }
                let _ = pipe.write_all(stdin.as_bytes());
            });
    }

    let now = Instant::now();
    let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
    PROCS.lock().insert(
        id,
        Arc::new(Mutex::new(Proc {
            child,
            pgid: pid,
            out,
            err,
            status: None,
            started: now,
            deadline: timeout.map(|t| now + t),
            term_sent: None,
            kill_sent: false,
            timed_out: false,
            exited_at: None,
            finished: false,
            lingering: Vec::new(),
        })),
    );
    log::debug!("proc {id} -> pid {pid} ({})", argv[0]);
    Ok(json!({ "id": id, "pid": pid }))
}

/// One step: wait up to `waitMs` for output, return what arrived, apply the
/// timeout and the exit rules. `done` says the caller can stop and [close].
pub fn step(v: &Value) -> Result<Value> {
    let id = id_of(v)?;
    let wait = v.get("waitMs").and_then(Value::as_u64).unwrap_or(100).min(2_000) as i32;
    let limit = v
        .get("maxBytes")
        .and_then(Value::as_u64)
        .unwrap_or(256 * 1024)
        .max(4 * 1024) as usize;
    let proc = get(id)?;
    let mut p = proc.lock();

    let mut fds: Vec<libc::pollfd> = [p.out.fd, p.err.fd]
        .iter()
        .filter(|fd| **fd >= 0)
        .map(|fd| libc::pollfd { fd: *fd, events: libc::POLLIN, revents: 0 })
        .collect();
    if !fds.is_empty() {
        if wait > 0 && !p.finished {
            unsafe { libc::poll(fds.as_mut_ptr(), fds.len() as libc::nfds_t, wait) };
        }
    } else if !p.finished && p.reap().is_none() && wait > 0 {
        // The command closed its output but is still running.
        p.wait_leader(Duration::from_millis(wait as u64));
    }

    let stdout = p.out.drain(limit);
    let stderr = p.err.drain(limit);
    let status = p.reap();
    let now = Instant::now();
    if status.is_some() && p.exited_at.is_none() {
        p.exited_at = Some(now);
    }
    let open = p.out.is_open() || p.err.is_open();

    if !p.finished {
        if let Some(exited) = p.exited_at {
            if !open {
                p.finished = true;
            } else if now.duration_since(exited) >= PIPE_GRACE {
                // Something the command started still holds its output.
                p.lingering = group_members(p.pgid);
                p.finished = true;
            }
        } else if let Some(sent) = p.term_sent {
            let since = now.duration_since(sent);
            if !p.kill_sent && since >= TERM_GRACE {
                p.signal_group(libc::SIGKILL);
                p.kill_sent = true;
            } else if p.kill_sent && since >= TERM_GRACE + KILL_WAIT {
                // Not even SIGKILL ended it (uninterruptible I/O): stop
                // waiting; close() hands it to a reaper thread.
                p.finished = true;
            }
        } else if p.deadline.is_some_and(|d| now >= d) {
            p.timed_out = true;
            p.signal_group(libc::SIGTERM);
            p.term_sent = Some(now);
        }
    }

    let lingering: Vec<Value> = if p.finished {
        p.lingering.iter().map(|(pid, name)| json!({ "pid": pid, "name": name })).collect()
    } else {
        Vec::new()
    };
    Ok(json!({
        "stdout": stdout,
        "stderr": stderr,
        "running": status.is_none(),
        "exitCode": status.and_then(|s| s.code()),
        "signal": status.and_then(|s| s.signal()),
        "timedOut": p.timed_out,
        "done": p.finished,
        // Still open at the end: a detached process holds the output.
        "outputHeld": p.finished && open,
        "lingering": lingering,
        "elapsedMs": p.started.elapsed().as_millis() as u64,
    }))
}

/// Release a process. Unless it finished on its own with nothing left behind,
/// its whole group is stopped first: SIGTERM, `graceMs` to exit, then SIGKILL.
/// That is what cancelling a tool call, and a timeout, end in.
pub fn close(v: &Value) -> Result<Value> {
    let id = id_of(v)?;
    let grace = Duration::from_millis(v.get("graceMs").and_then(Value::as_u64).unwrap_or(300).min(5_000));
    let Some(proc) = PROCS.lock().remove(&id) else {
        return Ok(json!({ "closed": false }));
    };
    let reaped = {
        let mut p = proc.lock();
        let stop = !p.finished || !p.lingering.is_empty() || p.reap().is_none();
        if stop {
            p.signal_group(libc::SIGTERM);
            p.wait_leader(grace);
            // The group, not just the leader: a background job of the command
            // can outlive it and ignore SIGTERM.
            p.signal_group(libc::SIGKILL);
            p.wait_leader(Duration::from_millis(500));
        }
        p.out.close();
        p.err.close();
        p.status.is_some()
    };
    if !reaped {
        // Unkillable for now (uninterruptible I/O). Reap it whenever it does
        // exit, so no zombie is left behind.
        let _ = std::thread::Builder::new()
            .name("apkcli-reaper".into())
            .spawn(move || {
                let _ = proc.lock().child.wait();
            });
    }
    Ok(json!({ "closed": true }))
}

/// Live processes in process group `pgid`, by pid and name, from /proc.
fn group_members(pgid: libc::pid_t) -> Vec<(i32, String)> {
    let mut out = Vec::new();
    let Ok(dir) = std::fs::read_dir("/proc") else {
        return out;
    };
    for entry in dir.flatten() {
        let Some(pid) = entry.file_name().to_str().and_then(|s| s.parse::<i32>().ok()) else {
            continue;
        };
        let Ok(stat) = std::fs::read_to_string(format!("/proc/{pid}/stat")) else {
            continue;
        };
        // "pid (comm) state ppid pgrp ...": comm may itself contain spaces
        // and parentheses, so it is cut out by the outermost pair.
        let (Some(open), Some(close)) = (stat.find('('), stat.rfind(')')) else {
            continue;
        };
        let fields: Vec<&str> = stat[close + 1..].split_whitespace().collect();
        let zombie = fields.first() == Some(&"Z");
        if !zombie && fields.get(2).and_then(|g| g.parse::<i32>().ok()) == Some(pgid) {
            out.push((pid, stat[open + 1..close].to_string()));
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn shell() -> &'static str {
        if cfg!(target_os = "android") {
            "/system/bin/sh"
        } else {
            "/bin/sh"
        }
    }

    /// The loop ShellRunner runs, without the coroutine around it.
    fn run(command: &str, timeout_ms: u64) -> (Value, String, String, Duration) {
        run_with(command, timeout_ms, false)
    }

    /// [run] with the output on a terminal instead of two pipes.
    fn run_tty(command: &str, timeout_ms: u64) -> (Value, String, String, Duration) {
        run_with(command, timeout_ms, true)
    }

    fn run_with(command: &str, timeout_ms: u64, tty: bool) -> (Value, String, String, Duration) {
        let started = Instant::now();
        let spawned = spawn(&json!({
            "command": command, "shell": shell(), "cwd": "/", "clearEnv": false,
            "timeoutMs": timeout_ms, "tty": tty, "cols": 100, "rows": 30,
        }))
        .unwrap();
        let id = spawned["id"].as_i64().unwrap();
        let (mut out, mut err) = (String::new(), String::new());
        let last = loop {
            let r = step(&json!({ "id": id, "waitMs": 50 })).unwrap();
            out.push_str(r["stdout"].as_str().unwrap());
            err.push_str(r["stderr"].as_str().unwrap());
            if r["done"] == true {
                break r;
            }
            assert!(started.elapsed() < Duration::from_secs(30), "never finished: {command}");
        };
        close(&json!({ "id": id })).unwrap();
        (last, out, err, started.elapsed())
    }

    #[test]
    fn runs_and_reports_exit_code() {
        let (r, out, err, _) = run("printf hello; printf oops >&2; exit 3", 10_000);
        assert_eq!(r["exitCode"], 3);
        assert_eq!(out, "hello");
        assert_eq!(err, "oops");
        assert_eq!(r["timedOut"], false);
    }

    #[test]
    fn timeout_stops_the_whole_group() {
        // The shell forks `sleep` as its child: killing only the shell, as
        // the old code did, left the sleep (and the pipes) alive.
        let (r, _, _, took) = run("sleep 30; echo late", 300);
        assert_eq!(r["timedOut"], true);
        assert!(took < Duration::from_secs(8), "took {took:?}");
    }

    #[test]
    fn background_process_holding_the_pipe_does_not_hang() {
        // `sleep &` inherits stdout. Waiting for EOF waited for the sleep.
        let (r, out, _, took) = run("echo started; sleep 30 & echo done", 60_000);
        assert_eq!(r["exitCode"], 0);
        assert!(out.contains("started") && out.contains("done"), "{out}");
        assert!(took < Duration::from_secs(5), "took {took:?}");
        let lingering = r["lingering"].as_array().unwrap();
        assert!(lingering.iter().any(|p| p["name"] == "sleep"), "{r}");
    }

    #[test]
    fn redirected_background_job_is_left_alone() {
        // Output redirected: it does not hold the pipes, so it is not the
        // command's business any more, and nothing is reported or stopped.
        let (r, _, _, took) = run("sleep 2 >/dev/null 2>&1 & echo ok", 60_000);
        assert_eq!(r["exitCode"], 0);
        assert!(r["lingering"].as_array().unwrap().is_empty(), "{r}");
        assert!(took < Duration::from_secs(2), "took {took:?}");
    }

    #[test]
    fn output_arrives_before_the_command_ends() {
        let spawned = spawn(&json!({
            "command": "echo first; sleep 1; echo second", "shell": shell(), "cwd": "/",
            "clearEnv": false, "timeoutMs": 10_000,
        }))
        .unwrap();
        let id = spawned["id"].as_i64().unwrap();
        let started = Instant::now();
        let mut seen = String::new();
        while started.elapsed() < Duration::from_millis(700) {
            let r = step(&json!({ "id": id, "waitMs": 50 })).unwrap();
            seen.push_str(r["stdout"].as_str().unwrap());
        }
        assert!(seen.contains("first") && !seen.contains("second"), "{seen}");
        close(&json!({ "id": id })).unwrap();
    }

    #[test]
    fn utf8_split_across_reads_is_kept_whole() {
        let mut partial = Vec::new();
        let text = "ё".as_bytes();
        let first = decode(vec![b'a', text[0]], &mut partial, false);
        assert_eq!(first, "a");
        let mut rest = std::mem::take(&mut partial);
        rest.push(text[1]);
        assert_eq!(decode(rest, &mut partial, false), "ё");
        assert_eq!(decode(vec![0xff, b'b'], &mut partial, false), "\u{FFFD}b");
    }

    #[test]
    fn stdin_is_fed_and_closed() {
        let spawned = spawn(&json!({
            "command": "cat", "shell": shell(), "cwd": "/", "clearEnv": false,
            "stdin": "piped in", "timeoutMs": 10_000,
        }))
        .unwrap();
        let id = spawned["id"].as_i64().unwrap();
        let mut out = String::new();
        loop {
            let r = step(&json!({ "id": id, "waitMs": 50 })).unwrap();
            out.push_str(r["stdout"].as_str().unwrap());
            if r["done"] == true {
                break;
            }
        }
        close(&json!({ "id": id })).unwrap();
        assert_eq!(out, "piped in");
    }

    #[test]
    fn tty_mode_puts_both_streams_on_a_terminal() {
        let (r, out, err, _) = run_tty(
            "[ -t 1 ] && echo out-tty; [ -t 2 ] && echo err-tty >&2; [ -t 0 ] || echo in-not-tty",
            10_000,
        );
        assert_eq!(r["exitCode"], 0, "{r}");
        // One stream, in the order it was written, with the terminal's line
        // endings; stdin stays off the terminal, so nothing can wait on it.
        assert_eq!(err, "");
        assert!(out.contains("out-tty\r\nerr-tty\r\nin-not-tty\r\n"), "{out:?}");
    }

    #[test]
    fn tty_mode_has_the_size_it_was_given() {
        // stty reads the terminal from stdin, so hand it the one on stdout.
        let (r, out, _, _) = run_tty("stty size <&1", 10_000);
        assert_eq!(r["exitCode"], 0, "{r} {out:?}");
        assert!(out.contains("30 100"), "{out:?}");
    }

    #[test]
    fn tty_mode_has_no_controlling_terminal() {
        // A prompt that opens /dev/tty must fail at once (ENXIO), not wait
        // for input. In a subshell: a failed redirection on `exec` ends the
        // shell it runs in.
        let (r, out, _, took) = run_tty("(exec 3</dev/tty) 2>/dev/null && echo opened || echo refused", 10_000);
        assert_eq!(r["exitCode"], 0, "{r}");
        assert!(out.contains("refused") && !out.contains("opened"), "{out:?}");
        assert!(took < Duration::from_secs(3), "took {took:?}");
    }

    #[test]
    fn tty_mode_background_holder_does_not_hang() {
        // The background job inherits the terminal, as it inherits a pipe.
        let (r, out, _, took) = run_tty("echo started; sleep 30 & echo done", 60_000);
        assert_eq!(r["exitCode"], 0);
        assert!(out.contains("started") && out.contains("done"), "{out:?}");
        assert!(took < Duration::from_secs(5), "took {took:?}");
        let lingering = r["lingering"].as_array().unwrap();
        assert!(lingering.iter().any(|p| p["name"] == "sleep"), "{r}");
    }

    #[test]
    fn tty_mode_timeout_stops_the_whole_group() {
        let (r, _, _, took) = run_tty("sleep 30; echo late", 300);
        assert_eq!(r["timedOut"], true);
        assert!(took < Duration::from_secs(8), "took {took:?}");
    }

    #[test]
    fn close_while_running_kills_it() {
        let spawned = spawn(&json!({
            "command": "sleep 30", "shell": shell(), "cwd": "/", "clearEnv": false,
        }))
        .unwrap();
        let pid = spawned["pid"].as_i64().unwrap() as libc::pid_t;
        let started = Instant::now();
        close(&json!({ "id": spawned["id"] })).unwrap();
        assert!(started.elapsed() < Duration::from_secs(3));
        // The group is gone: signal 0 finds nobody.
        assert!(unsafe { libc::kill(-pid, 0) } != 0);
    }
}
