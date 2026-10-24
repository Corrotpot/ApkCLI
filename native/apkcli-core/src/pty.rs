//! Process execution: captured one-shots and interactive PTY sessions.
//!
//! Two distinct paths, because they have different failure modes:
//!
//!   * Captured commands — `sh -c "..."` with pipes, stepped by the caller
//!     with a timeout and live output. This is what `sh_exec` uses; it lives
//!     in [crate::proc], and its ops (`proc.*`) are routed through here.
//!
//!   * PTY sessions — a real master/slave pair so programs that check
//!     `isatty()` behave (vi, less, python's REPL, progress bars, colour). This
//!     needs `fork()` directly: `std::process` cannot make the child the
//!     session leader of a new controlling terminal.
//!
//! Android specifics worth knowing:
//!   * `/dev/ptmx` is reachable from an untrusted app sandbox. This is the one
//!     piece of terminal infrastructure that does *not* need root.
//!   * bionic's `<pty.h>` has `openpty`, but its availability per API level is
//!     inconsistent across NDK versions, so the pair is opened by hand through
//!     `/dev/ptmx` + `TIOCGPTN`/`TIOCSPTLCK`. Those ioctl numbers are identical
//!     on every Linux ABI we target.
//!   * Between `fork()` and `execve()` the child may only call
//!     async-signal-safe functions. Every allocation (argv, envp, path) happens
//!     before the fork for that reason.

use crate::error::{CoreError, Result};
use once_cell::sync::Lazy;
use parking_lot::Mutex;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::os::unix::io::RawFd;
use std::path::{Path, PathBuf};
use std::time::Duration;

// The request numbers are the same on arm64, armv7, x86 and x86_64 Linux, but
// the *type* of ioctl's second parameter is not portable: `libc::Ioctl` is
// `c_int` on Android and musl, and `c_ulong` on glibc. Writing the constants as
// u32 and casting through `libc::Ioctl` compiles on all of them. The cast is
// deliberately from u32 — 0x80045430 does not fit in an i32, so it has to wrap
// to a negative request number, which is what the C macros produce too.
const TIOCGPTN: libc::Ioctl = 0x8004_5430u32 as libc::Ioctl;
const TIOCSPTLCK: libc::Ioctl = 0x4004_5431u32 as libc::Ioctl;

struct Session {
    master: RawFd,
    pid: libc::pid_t,
    exit_status: Option<i32>,
}

static SESSIONS: Lazy<Mutex<HashMap<i64, Session>>> = Lazy::new(|| Mutex::new(HashMap::new()));
static NEXT_ID: Lazy<Mutex<i64>> = Lazy::new(|| Mutex::new(1));

pub fn call(op: &str, payload: &str) -> Result<String> {
    let v: Value = if payload.trim().is_empty() {
        json!({})
    } else {
        serde_json::from_str(payload)?
    };
    let out = match op {
        "proc.spawn" | "proc.step" | "proc.signal" | "proc.close" => crate::proc::call(op, &v)?,
        "pty.open" => pty_open(&v)?,
        "pty.resize" => pty_resize(&v)?,
        "pty.wait" => pty_wait(&v)?,
        "pty.status" => pty_status(&v)?,
        "pty.kill" => pty_kill(&v)?,
        "pty.close" => pty_close(&v)?,
        "pty.list" => pty_list()?,
        "which" => which_op(&v)?,
        other => return Err(CoreError::invalid(format!("unknown pty op '{other}'"))),
    };
    Ok(out.to_string())
}

/* ------------------------------------------------------------------ helpers */

pub(crate) fn argv_of(v: &Value) -> Result<Vec<String>> {
    if let Some(arr) = v.get("argv").and_then(Value::as_array) {
        let out: Vec<String> = arr
            .iter()
            .filter_map(Value::as_str)
            .map(str::to_owned)
            .collect();
        if out.is_empty() {
            return Err(CoreError::invalid("argv is empty"));
        }
        return Ok(out);
    }
    // `command` is run through the shell, which is the normal case: the model
    // writes pipelines and redirections, not exec vectors.
    let cmd = v
        .get("command")
        .and_then(Value::as_str)
        .ok_or_else(|| CoreError::invalid("need either 'argv' or 'command'"))?;
    let shell = v
        .get("shell")
        .and_then(Value::as_str)
        .unwrap_or("/system/bin/sh");
    Ok(vec![shell.to_string(), "-c".to_string(), cmd.to_string()])
}

pub(crate) fn env_of(v: &Value) -> Vec<(String, String)> {
    v.get("env")
        .and_then(Value::as_object)
        .map(|m| {
            m.iter()
                .filter_map(|(k, val)| val.as_str().map(|s| (k.clone(), s.to_string())))
                .collect()
        })
        .unwrap_or_default()
}

/// PATH resolution done here rather than by `execvp`, so the exec policy is
/// applied in one place and the error message can say *why* a lookup failed.
pub fn which(program: &str, path_var: &str) -> Option<PathBuf> {
    if program.contains('/') {
        let p = PathBuf::from(program);
        return if is_executable(&p) { Some(p) } else { None };
    }
    for dir in path_var.split(':').filter(|d| !d.is_empty()) {
        let candidate = Path::new(dir).join(program);
        if is_executable(&candidate) {
            return Some(candidate);
        }
        // The prefix keeps binaries as lib*.so symlinks, because that is the
        // only name shape the APK installer unpacks into an exec-permitted
        // directory. Accept the bare name too.
        let wrapped = Path::new(dir).join(format!("lib{program}.so"));
        if is_executable(&wrapped) {
            return Some(wrapped);
        }
    }
    None
}

fn is_executable(p: &Path) -> bool {
    let Ok(c) = CString::new(p.as_os_str().as_encoded_bytes()) else {
        return false;
    };
    unsafe { libc::access(c.as_ptr(), libc::X_OK) == 0 }
}

fn which_op(v: &Value) -> Result<Value> {
    let program = v
        .get("program")
        .and_then(Value::as_str)
        .ok_or_else(|| CoreError::invalid("missing 'program'"))?;
    let path_var = v
        .get("path")
        .and_then(Value::as_str)
        .unwrap_or("/system/bin:/system/xbin");
    Ok(json!({
        "program": program,
        "resolved": which(program, path_var).map(|p| p.to_string_lossy().to_string()),
    }))
}

/* -------------------------------------------------------------- PTY sessions */

pub(crate) fn open_ptmx() -> Result<(RawFd, PathBuf)> {
    let path = CString::new("/dev/ptmx").unwrap();
    let master = unsafe { libc::open(path.as_ptr(), libc::O_RDWR | libc::O_CLOEXEC) };
    if master < 0 {
        return Err(CoreError::last_errno("open(/dev/ptmx)"));
    }

    let mut unlock: libc::c_int = 0;
    if unsafe { libc::ioctl(master, TIOCSPTLCK, &mut unlock) } < 0 {
        unsafe { libc::close(master) };
        return Err(CoreError::last_errno("ioctl(TIOCSPTLCK)"));
    }

    let mut ptn: libc::c_int = 0;
    if unsafe { libc::ioctl(master, TIOCGPTN, &mut ptn) } < 0 {
        unsafe { libc::close(master) };
        return Err(CoreError::last_errno("ioctl(TIOCGPTN)"));
    }

    Ok((master, PathBuf::from(format!("/dev/pts/{ptn}"))))
}

pub(crate) fn set_winsize(fd: RawFd, cols: u16, rows: u16) {
    let ws = libc::winsize {
        ws_row: rows,
        ws_col: cols,
        ws_xpixel: 0,
        ws_ypixel: 0,
    };
    unsafe {
        libc::ioctl(fd, libc::TIOCSWINSZ, &ws);
    }
}

fn pty_open(v: &Value) -> Result<Value> {
    let argv = argv_of(v)?;
    let cwd = v.get("cwd").and_then(Value::as_str).unwrap_or("/").to_string();
    let cols = v.get("cols").and_then(Value::as_u64).unwrap_or(80) as u16;
    let rows = v.get("rows").and_then(Value::as_u64).unwrap_or(24) as u16;

    let path_var = env_of(v)
        .iter()
        .find(|(k, _)| k == "PATH")
        .map(|(_, p)| p.clone())
        .unwrap_or_else(|| "/system/bin:/system/xbin".to_string());

    let program = which(&argv[0], &path_var).ok_or_else(|| {
        CoreError::not_found(format!(
            "{} not found on PATH ({}). Android has no /bin or /usr/bin; \
             the prefix must be on PATH.",
            argv[0], path_var
        ))
    })?;

    // --- everything that allocates happens before fork() -------------------
    let c_program = CString::new(program.as_os_str().as_encoded_bytes())
        .map_err(|_| CoreError::invalid("program path contains NUL"))?;
    let c_args: Vec<CString> = argv
        .iter()
        .map(|a| CString::new(a.as_str()).unwrap_or_else(|_| CString::new("").unwrap()))
        .collect();
    let mut argv_ptrs: Vec<*const libc::c_char> = c_args.iter().map(|c| c.as_ptr()).collect();
    argv_ptrs.push(std::ptr::null());

    let c_envs: Vec<CString> = env_of(v)
        .iter()
        .map(|(k, val)| {
            CString::new(format!("{k}={val}")).unwrap_or_else(|_| CString::new("X=").unwrap())
        })
        .collect();
    let mut env_ptrs: Vec<*const libc::c_char> = c_envs.iter().map(|c| c.as_ptr()).collect();
    env_ptrs.push(std::ptr::null());

    let c_cwd = CString::new(cwd.as_str()).map_err(|_| CoreError::invalid("cwd contains NUL"))?;

    let (master, slave_path) = open_ptmx()?;
    let c_slave = CString::new(slave_path.as_os_str().as_encoded_bytes())
        .map_err(|_| CoreError::internal("slave path contains NUL"))?;
    set_winsize(master, cols, rows);

    let pid = unsafe { libc::fork() };
    if pid < 0 {
        unsafe { libc::close(master) };
        return Err(CoreError::last_errno("fork"));
    }

    if pid == 0 {
        // ---- child: async-signal-safe calls only, then exec or _exit -------
        unsafe {
            libc::close(master);
            if libc::setsid() < 0 {
                libc::_exit(126);
            }
            let slave = libc::open(c_slave.as_ptr(), libc::O_RDWR);
            if slave < 0 {
                libc::_exit(126);
            }
            // Make the slave our controlling terminal.
            if libc::ioctl(slave, libc::TIOCSCTTY, 0) < 0 {
                libc::_exit(126);
            }
            libc::dup2(slave, libc::STDIN_FILENO);
            libc::dup2(slave, libc::STDOUT_FILENO);
            libc::dup2(slave, libc::STDERR_FILENO);
            if slave > libc::STDERR_FILENO {
                libc::close(slave);
            }
            if libc::chdir(c_cwd.as_ptr()) < 0 {
                // Falling back to / is better than refusing to start a shell.
                let root = b"/\0";
                libc::chdir(root.as_ptr() as *const libc::c_char);
            }
            // Reset signal dispositions the JVM installed; otherwise Ctrl-C in
            // the terminal does nothing.
            libc::signal(libc::SIGINT, libc::SIG_DFL);
            libc::signal(libc::SIGQUIT, libc::SIG_DFL);
            libc::signal(libc::SIGTERM, libc::SIG_DFL);
            libc::signal(libc::SIGTSTP, libc::SIG_DFL);
            libc::signal(libc::SIGTTIN, libc::SIG_DFL);
            libc::signal(libc::SIGTTOU, libc::SIG_DFL);
            libc::signal(libc::SIGCHLD, libc::SIG_DFL);
            libc::signal(libc::SIGPIPE, libc::SIG_DFL);

            libc::execve(c_program.as_ptr(), argv_ptrs.as_ptr(), env_ptrs.as_ptr());
            libc::_exit(127);
        }
    }

    // ---- parent -----------------------------------------------------------
    let id = {
        let mut next = NEXT_ID.lock();
        let id = *next;
        *next += 1;
        id
    };
    SESSIONS.lock().insert(
        id,
        Session {
            master,
            pid,
            exit_status: None,
        },
    );

    log::info!("pty {id} -> pid {pid} ({})", program.display());
    Ok(json!({
        "id": id,
        "pid": pid,
        "program": program.to_string_lossy(),
        "slave": slave_path.to_string_lossy(),
    }))
}

fn with_session<T>(id: i64, f: impl FnOnce(&mut Session) -> Result<T>) -> Result<T> {
    let mut guard = SESSIONS.lock();
    let sess = guard
        .get_mut(&id)
        .ok_or_else(|| CoreError::not_found(format!("pty session {id}")))?;
    f(sess)
}

/// Blocking read of up to `buf.len()` bytes. Called from a dedicated Kotlin IO
/// thread per session.
///
/// EIO here means "the slave side went away", i.e. the child exited. That is
/// normal termination, not an error, so it is reported as EOF.
pub fn read_master(id: i64, buf: &mut [u8]) -> Result<usize> {
    let fd = with_session(id, |s| Ok(s.master))?;
    loop {
        let n = unsafe { libc::read(fd, buf.as_mut_ptr() as *mut libc::c_void, buf.len()) };
        if n >= 0 {
            return Ok(n as usize);
        }
        let errno = std::io::Error::last_os_error().raw_os_error().unwrap_or(0);
        match errno {
            libc::EINTR => continue,
            libc::EIO => return Ok(0),
            _ => return Err(CoreError::Errno { call: "read", errno }),
        }
    }
}

pub fn write_master(id: i64, data: &[u8]) -> Result<usize> {
    let fd = with_session(id, |s| Ok(s.master))?;
    let mut written = 0usize;
    while written < data.len() {
        let n = unsafe {
            libc::write(
                fd,
                data[written..].as_ptr() as *const libc::c_void,
                data.len() - written,
            )
        };
        if n > 0 {
            written += n as usize;
            continue;
        }
        let errno = std::io::Error::last_os_error().raw_os_error().unwrap_or(0);
        if errno == libc::EINTR {
            continue;
        }
        return Err(CoreError::Errno { call: "write", errno });
    }
    Ok(written)
}

fn pty_resize(v: &Value) -> Result<Value> {
    let id = v
        .get("id")
        .and_then(Value::as_i64)
        .ok_or_else(|| CoreError::invalid("missing 'id'"))?;
    let cols = v.get("cols").and_then(Value::as_u64).unwrap_or(80) as u16;
    let rows = v.get("rows").and_then(Value::as_u64).unwrap_or(24) as u16;
    let (fd, pid) = with_session(id, |s| Ok((s.master, s.pid)))?;
    set_winsize(fd, cols, rows);
    // Tell the foreground process group the geometry changed; without this a
    // running `less` or `vim` keeps drawing at the old size.
    unsafe {
        libc::kill(-pid, libc::SIGWINCH);
    }
    Ok(json!({ "ok": true, "cols": cols, "rows": rows }))
}

fn reap(sess: &mut Session) -> Option<i32> {
    if let Some(st) = sess.exit_status {
        return Some(st);
    }
    let mut status: libc::c_int = 0;
    let r = unsafe { libc::waitpid(sess.pid, &mut status, libc::WNOHANG) };
    if r == sess.pid {
        let code = if libc::WIFEXITED(status) {
            libc::WEXITSTATUS(status)
        } else if libc::WIFSIGNALED(status) {
            128 + libc::WTERMSIG(status)
        } else {
            -1
        };
        sess.exit_status = Some(code);
        Some(code)
    } else {
        None
    }
}

fn pty_wait(v: &Value) -> Result<Value> {
    let id = v
        .get("id")
        .and_then(Value::as_i64)
        .ok_or_else(|| CoreError::invalid("missing 'id'"))?;
    let status = with_session(id, |s| Ok(reap(s)))?;
    Ok(json!({ "id": id, "running": status.is_none(), "exitCode": status }))
}

/// What a session is doing right now.
///
/// `tcgetpgrp` on the master is the authoritative answer to "what is running
/// in this terminal": the foreground process group is, by definition, the one
/// the kernel would deliver Ctrl-C to. Asking the process tree instead gets it
/// wrong for a pipeline, and guessing from the last line of output gets it
/// wrong for anything quiet.
///
/// The names come from `/proc`, which an app may read for its own uid. A
/// missing entry is not an error: the process can exit between the two calls,
/// and on some devices `/proc` is mounted with `hidepid`.
fn pty_status(v: &Value) -> Result<Value> {
    let id = v
        .get("id")
        .and_then(Value::as_i64)
        .ok_or_else(|| CoreError::invalid("missing 'id'"))?;
    let (fd, pid, status) = with_session(id, |s| Ok((s.master, s.pid, reap(s))))?;

    let foreground = unsafe { libc::tcgetpgrp(fd) };
    let foreground = if foreground > 0 && status.is_none() {
        Some(foreground)
    } else {
        None
    };

    Ok(json!({
        "id": id,
        "pid": pid,
        "running": status.is_none(),
        "exitCode": status,
        "foregroundPid": foreground,
        // None while the shell itself is in the foreground, i.e. at a prompt
        // with nothing running.
        "foregroundName": foreground
            .filter(|fg| *fg != pid)
            .and_then(proc_comm),
        "cwd": proc_cwd(pid),
    }))
}

/// A process's name as the kernel reports it: the executable's basename,
/// truncated to 15 characters by Linux itself.
fn proc_comm(pid: libc::pid_t) -> Option<String> {
    let text = std::fs::read_to_string(format!("/proc/{pid}/comm")).ok()?;
    let name = text.trim().to_string();
    if name.is_empty() {
        None
    } else {
        Some(name)
    }
}

/// Where the shell is now, which is where a `cd` left it.
fn proc_cwd(pid: libc::pid_t) -> Option<String> {
    let target = std::fs::read_link(format!("/proc/{pid}/cwd")).ok()?;
    Some(target.to_string_lossy().into_owned())
}

fn pty_kill(v: &Value) -> Result<Value> {
    let id = v
        .get("id")
        .and_then(Value::as_i64)
        .ok_or_else(|| CoreError::invalid("missing 'id'"))?;
    let signal = v.get("signal").and_then(Value::as_i64).unwrap_or(libc::SIGHUP as i64)
        as libc::c_int;
    let pid = with_session(id, |s| Ok(s.pid))?;
    // Negative pid signals the whole process group, so a pipeline dies with
    // its shell instead of leaking orphans.
    let rc = unsafe { libc::kill(-pid, signal) };
    Ok(json!({ "ok": rc == 0, "pid": pid, "signal": signal }))
}

fn pty_close(v: &Value) -> Result<Value> {
    let id = v
        .get("id")
        .and_then(Value::as_i64)
        .ok_or_else(|| CoreError::invalid("missing 'id'"))?;
    let mut guard = SESSIONS.lock();
    let Some(sess) = guard.remove(&id) else {
        // Already released. Idempotent on purpose: the Kotlin side closes both
        // when the reader hits EOF and when the user closes the tab.
        return Ok(json!({ "closed": false }));
    };
    // Drop the lock before sleeping below, or a concurrent read/write on
    // another session blocks for the duration.
    drop(guard);

    let already_reaped = sess.exit_status.is_some();

    unsafe {
        // Negative pid: the whole process group, so a pipeline dies with its
        // shell instead of leaking orphans.
        libc::kill(-sess.pid, libc::SIGHUP);
        libc::close(sess.master);
    }

    // Reap, or leave a zombie behind for the lifetime of the process.
    //
    // A single WNOHANG immediately after the signal almost never succeeds —
    // the child has not been scheduled yet. Polling briefly collects it in
    // practice without blocking the caller for long enough to matter. If it is
    // still alive after this, SIGKILL and one final attempt.
    let mut status: libc::c_int = 0;
    if !already_reaped {
        let mut reaped = false;
        for _ in 0..REAP_ATTEMPTS {
            if unsafe { libc::waitpid(sess.pid, &mut status, libc::WNOHANG) } == sess.pid {
                reaped = true;
                break;
            }
            std::thread::sleep(Duration::from_millis(REAP_INTERVAL_MS));
        }
        if !reaped {
            unsafe {
                libc::kill(-sess.pid, libc::SIGKILL);
                // SIGKILL cannot be caught, so one short wait is enough.
                std::thread::sleep(Duration::from_millis(REAP_INTERVAL_MS));
                libc::waitpid(sess.pid, &mut status, libc::WNOHANG);
            }
            log::warn!("pty {id} (pid {}) did not exit on SIGHUP; killed", sess.pid);
        }
    }

    // `sess` is dropped here; the entry is already out of the map and the fd is
    // closed, so there is no state left to write back.
    Ok(json!({ "closed": true }))
}

/// Total ~60ms of polling, which is below the threshold where a UI action feels
/// delayed and long enough to collect a shell that was asked to hang up.
const REAP_ATTEMPTS: u32 = 12;
const REAP_INTERVAL_MS: u64 = 5;

fn pty_list() -> Result<Value> {
    let mut guard = SESSIONS.lock();
    let mut out = Vec::new();
    for (id, sess) in guard.iter_mut() {
        let status = reap(sess);
        out.push(json!({
            "id": id,
            "pid": sess.pid,
            "running": status.is_none(),
            "exitCode": status,
        }));
    }
    Ok(json!({ "sessions": out }))
}

/// Resolve a symlink chain to its final target. Used by the bootstrap check to
/// prove that `$PREFIX/bin/sh` really lands inside nativeLibraryDir.
pub fn realpath(path: &str) -> Result<String> {
    let c = CString::new(path).map_err(|_| CoreError::invalid("path contains NUL"))?;
    let mut buf = vec![0u8; libc::PATH_MAX as usize];
    let p = unsafe { libc::realpath(c.as_ptr(), buf.as_mut_ptr() as *mut libc::c_char) };
    if p.is_null() {
        return Err(CoreError::last_errno("realpath"));
    }
    let s = unsafe { CStr::from_ptr(p) }
        .to_string_lossy()
        .into_owned();
    Ok(s)
}
