//! `LD_PRELOAD` shim that makes a Linux-shaped userland work on Android.
//!
//! Three problems; the first two otherwise make most real-world scripts fail:
//!
//! 1. **Absolute interpreter paths.** Android has no `/bin`, no `/usr/bin`,
//!    no `/bin/sh`. Any script with `#!/bin/bash`, any Makefile calling
//!    `/usr/bin/env`, any configure script — all fail with ENOENT.
//!
//! 2. **Shebangs the kernel resolves before we can.** `execve("script.sh")`
//!    makes the *kernel* read `#!/bin/sh` and look for `/bin/sh`. There is no
//!    userspace hook at that point, so the rewrite has to happen before the
//!    syscall: read the shebang ourselves, resolve the interpreter, and exec
//!    the interpreter with the script as its first argument.
//!
//! 3. **Termux's prefix.** Installed Termux packages may exec helpers at
//!    `/data/data/com.termux/files/usr/...` when the package manager could not
//!    relocate the path inside a binary; see `TERMUX_PREFIX`.
//!
//! This is the same approach `termux-exec` takes, reimplemented small.
//!
//! Loaded via `LD_PRELOAD=$PREFIX/lib/libexecshim.so` for every child the app
//! spawns. Configuration comes from the environment so the shim has no
//! compiled-in paths:
//!   `APKCLI_PREFIX`     — e.g. /data/data/dev.apkcli/files/usr
//!   `APKCLI_NATIVE_BIN` — the APK's nativeLibraryDir
//!
//! If either is unset the shim forwards every call untouched, so a
//! misconfigured environment degrades to plain Android behaviour rather than
//! breaking exec entirely.

use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int};
use std::ptr;

type ExecveFn = unsafe extern "C" fn(*const c_char, *const *const c_char, *const *const c_char) -> c_int;

const LINUXISH_PREFIXES: [&str; 6] = [
    "/bin/",
    "/usr/bin/",
    "/sbin/",
    "/usr/sbin/",
    "/usr/local/bin/",
    "/usr/local/sbin/",
];

/// bionic spells it `__errno()`; glibc spells it `__errno_location()`. The shim
/// is only shipped for Android, but keeping both lets it compile (and the
/// resolver logic be unit-tested) on a host machine.
#[cfg(target_os = "android")]
unsafe fn errno_ptr() -> *mut c_int {
    libc::__errno()
}

#[cfg(not(target_os = "android"))]
unsafe fn errno_ptr() -> *mut c_int {
    libc::__errno_location()
}

/// `RTLD_NEXT`, which the `libc` crate does not bind for Android.
///
/// bionic's value depends on pointer width, and the two are **not**
/// interchangeable:
///
/// ```text
///   LP64   RTLD_DEFAULT = 0            RTLD_NEXT = (void*)-1
///   ILP32  RTLD_DEFAULT = 0xffffffff   RTLD_NEXT = 0xfffffffe
/// ```
///
/// On a 32-bit build, `-1` *is* `RTLD_DEFAULT`. Using the LP64 constant on
/// armeabi-v7a would therefore not fail loudly: `dlsym` would resolve `execve`
/// starting from the global scope, find this very interposer, and every exec
/// would recurse until the stack ran out. Hence the explicit split, taken from
/// `<android/dlfcn.h>` in NDK r28.
#[cfg(all(target_os = "android", target_pointer_width = "64"))]
#[inline]
fn rtld_next() -> *mut libc::c_void {
    -1isize as *mut libc::c_void
}

#[cfg(all(target_os = "android", target_pointer_width = "32"))]
#[inline]
fn rtld_next() -> *mut libc::c_void {
    0xffff_fffeusize as *mut libc::c_void
}

#[cfg(not(target_os = "android"))]
#[inline]
fn rtld_next() -> *mut libc::c_void {
    libc::RTLD_NEXT
}

unsafe fn real_execve() -> Option<ExecveFn> {
    let name = b"execve\0";
    let sym = libc::dlsym(rtld_next(), name.as_ptr() as *const c_char);
    if sym.is_null() {
        None
    } else {
        Some(std::mem::transmute::<*mut libc::c_void, ExecveFn>(sym))
    }
}

fn env_var(key: &str) -> Option<String> {
    std::env::var(key).ok().filter(|v| !v.is_empty())
}

fn exists_executable(path: &str) -> bool {
    match CString::new(path) {
        Ok(c) => unsafe { libc::access(c.as_ptr(), libc::X_OK) == 0 },
        Err(_) => false,
    }
}

/// Termux's prefix, which its packages have compiled in.
///
/// The app's package manager rewrites it at install time, to a same-length
/// alias of `$APKCLI_PREFIX` where one fits. Where none fits (a debug build's
/// longer package name, a secondary user) only text files are rewritten, and a
/// binary that execs a helper by its built-in path, say Python's
/// `subprocess` running `.../usr/bin/sh`, arrives here with Termux's path.
const TERMUX_PREFIX: &str = "/data/data/com.termux/files/usr";

/// `/data/data/com.termux/files/usr/bin/x` -> `Some("/bin/x")`.
fn termux_relative(path: &str) -> Option<&str> {
    path.strip_prefix(TERMUX_PREFIX).filter(|rest| rest.starts_with('/'))
}

/// Map a Linux-looking absolute path onto something that exists on this device.
///
/// Order matters: the prefix wins over `/system/bin`, so a user who installed a
/// real GNU `sed` gets it instead of toybox's subset.
fn resolve_program(path: &str) -> Option<String> {
    if exists_executable(path) {
        return None; // already fine, no rewrite
    }

    // The same file under our prefix, wherever it is in the tree.
    if let Some(rest) = termux_relative(path) {
        if let Some(prefix) = env_var("APKCLI_PREFIX") {
            let candidate = format!("{prefix}{rest}");
            if exists_executable(&candidate) {
                return Some(candidate);
            }
        }
    }

    let linuxish = LINUXISH_PREFIXES.iter().any(|p| path.starts_with(*p))
        || termux_relative(path).is_some_and(|rest| rest.starts_with("/bin/"));
    let basename = if linuxish {
        path.rsplit('/').next()?
    } else if path.starts_with('/') {
        // Not a Linux-ish absolute path. Bare names are the dynamic
        // linker's problem (PATH), not ours.
        return None;
    } else {
        path
    };

    if let Some(prefix) = env_var("APKCLI_PREFIX") {
        let candidate = format!("{prefix}/bin/{basename}");
        if exists_executable(&candidate) {
            return Some(candidate);
        }
    }
    if let Some(native) = env_var("APKCLI_NATIVE_BIN") {
        // Everything executable inside the APK is named lib*.so, because that
        // is the only shape the installer unpacks into an exec-permitted dir.
        let candidate = format!("{native}/lib{basename}.so");
        if exists_executable(&candidate) {
            return Some(candidate);
        }
    }
    let system = format!("/system/bin/{basename}");
    if exists_executable(&system) {
        return Some(system);
    }
    None
}

/// Read a `#!` line without going through libc stdio (which could re-enter us).
fn read_shebang(path: &str) -> Option<(String, Option<String>)> {
    let c = CString::new(path).ok()?;
    let fd = unsafe { libc::open(c.as_ptr(), libc::O_RDONLY | libc::O_CLOEXEC) };
    if fd < 0 {
        return None;
    }
    let mut buf = [0u8; 256];
    let n = unsafe { libc::read(fd, buf.as_mut_ptr() as *mut libc::c_void, buf.len()) };
    unsafe { libc::close(fd) };
    if n < 3 || buf[0] != b'#' || buf[1] != b'!' {
        return None;
    }
    let line_end = buf[..n as usize]
        .iter()
        .position(|b| *b == b'\n' || *b == 0)
        .unwrap_or(n as usize);
    let line = std::str::from_utf8(&buf[2..line_end]).ok()?.trim();
    if line.is_empty() {
        return None;
    }
    let mut parts = line.splitn(2, char::is_whitespace);
    let interp = parts.next()?.to_string();
    let arg = parts.next().map(|s| s.trim().to_string()).filter(|s| !s.is_empty());
    Some((interp, arg))
}

struct Argv {
    _owned: Vec<CString>,
    ptrs: Vec<*const c_char>,
}

impl Argv {
    fn build(items: Vec<String>) -> Argv {
        let owned: Vec<CString> = items
            .into_iter()
            .map(|s| CString::new(s).unwrap_or_else(|_| CString::new("").unwrap()))
            .collect();
        let mut ptrs: Vec<*const c_char> = owned.iter().map(|c| c.as_ptr()).collect();
        ptrs.push(ptr::null());
        Argv { _owned: owned, ptrs }
    }
}

unsafe fn argv_to_vec(argv: *const *const c_char) -> Vec<String> {
    let mut out = Vec::new();
    if argv.is_null() {
        return out;
    }
    let mut i = 0isize;
    while !(*argv.offset(i)).is_null() {
        out.push(CStr::from_ptr(*argv.offset(i)).to_string_lossy().into_owned());
        i += 1;
        if i > 4096 {
            break; // malformed argv; do not run away
        }
    }
    out
}

/// The interposed `execve`.
#[no_mangle]
pub unsafe extern "C" fn execve(
    path: *const c_char,
    argv: *const *const c_char,
    envp: *const *const c_char,
) -> c_int {
    let Some(next) = real_execve() else {
        // Nothing to forward to; fail the way the kernel would.
        *errno_ptr() = libc::ENOSYS;
        return -1;
    };
    if path.is_null() {
        return next(path, argv, envp);
    }

    let original = CStr::from_ptr(path).to_string_lossy().into_owned();

    // Step 1: can the program itself be found, possibly under a rewritten path?
    let program = resolve_program(&original);
    let effective = program.clone().unwrap_or_else(|| original.clone());

    // Step 2: if the target is a script, resolve its interpreter ourselves.
    if let Some((interp, interp_arg)) = read_shebang(&effective) {
        let resolved_interp = resolve_program(&interp).unwrap_or(interp);
        if exists_executable(&resolved_interp) {
            let mut items = vec![resolved_interp.clone()];
            if let Some(a) = interp_arg {
                items.push(a);
            }
            items.push(effective.clone());
            // Drop the original argv[0]; everything after it is the script's
            // own arguments.
            let caller = argv_to_vec(argv);
            items.extend(caller.into_iter().skip(1));

            let rebuilt = Argv::build(items);
            let c_interp = match CString::new(resolved_interp) {
                Ok(c) => c,
                Err(_) => return next(path, argv, envp),
            };
            return next(c_interp.as_ptr(), rebuilt.ptrs.as_ptr(), envp);
        }
    }

    // Step 3: plain binary, possibly at a rewritten path.
    match program {
        Some(p) => match CString::new(p) {
            Ok(c) => next(c.as_ptr(), argv, envp),
            Err(_) => next(path, argv, envp),
        },
        None => next(path, argv, envp),
    }
}

/// `execv` is `execve` with the caller's environment.
#[no_mangle]
pub unsafe extern "C" fn execv(path: *const c_char, argv: *const *const c_char) -> c_int {
    execve(path, argv, environ_ptr())
}

/// `execvp`/`execvpe` do PATH lookup. Resolve against PATH here so the shebang
/// handling above also applies to bare command names.
#[no_mangle]
pub unsafe extern "C" fn execvp(file: *const c_char, argv: *const *const c_char) -> c_int {
    execvpe(file, argv, environ_ptr())
}

#[no_mangle]
pub unsafe extern "C" fn execvpe(
    file: *const c_char,
    argv: *const *const c_char,
    envp: *const *const c_char,
) -> c_int {
    if file.is_null() {
        *errno_ptr() = libc::EFAULT;
        return -1;
    }
    let name = CStr::from_ptr(file).to_string_lossy().into_owned();
    if name.contains('/') {
        return execve(file, argv, envp);
    }
    let path_var = env_var("PATH").unwrap_or_else(|| "/system/bin:/system/xbin".to_string());
    for dir in path_var.split(':').filter(|d| !d.is_empty()) {
        for candidate in [format!("{dir}/{name}"), format!("{dir}/lib{name}.so")] {
            if exists_executable(&candidate) {
                if let Ok(c) = CString::new(candidate) {
                    let rc = execve(c.as_ptr(), argv, envp);
                    // execve only returns on failure; keep trying other dirs
                    // unless the error is terminal.
                    let err = *errno_ptr();
                    if rc == -1 && (err == libc::ENOENT || err == libc::EACCES) {
                        continue;
                    }
                    return rc;
                }
            }
        }
    }
    *errno_ptr() = libc::ENOENT;
    -1
}

/// The process environment, for the `execv`/`execvp` family which inherits it.
///
/// bionic declares `extern char** environ;` in `<unistd.h>` and exports it, but
/// the `libc` crate only binds `libc::environ` for glibc-like targets, so on
/// Android it has to be declared here.
#[cfg(target_os = "android")]
unsafe fn environ_ptr() -> *const *const c_char {
    extern "C" {
        static mut environ: *mut *mut c_char;
    }
    environ as *const *const c_char
}

#[cfg(not(target_os = "android"))]
unsafe fn environ_ptr() -> *const *const c_char {
    libc::environ as *const *const c_char
}
