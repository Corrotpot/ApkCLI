# Running programs on Android without root

**English** · [Русский](ru/ANDROID_EXEC_NOTES.md)

This is the constraint the whole project is shaped around. Read it before
changing anything about how processes are started.

## The rule

Since **Android 10 (API 29)**, SELinux denies `execve()` on any file inside an
app's private data directory.

Mechanically: files under `/data/data/<pkg>/` carry the SELinux type
`app_data_file`. The `untrusted_app_29` domain and its successors — which is
what an app targeting API 29 or higher runs in — were not granted
`app_data_file:file execute_no_trans`. Apps targeting API 28 or lower run in
`untrusted_app_27`, which still has it.

Consequences that cost time if you do not know them:

* `chmod +x` does nothing. This is mandatory access control, not permission
  bits. The file is executable by its mode and still refused by the kernel.
* The failure is `EACCES` from `execve`, which surfaces through
  `ProcessBuilder` as a generic `IOException`. Nothing in the message says
  SELinux. `ExecPolicy.explainRefusal` exists so that the user and the model get
  told what is actually wrong, instead of an agent trying `chmod`, then `sudo`,
  then giving up.
* It applies to anything downloaded, generated, extracted or compiled at
  runtime. There is no way to opt out at runtime.

## What can be executed

### 1. `nativeLibraryDir`

`/data/app/~~<hash>/<pkg>-<hash>/lib/<arch>/`

Populated by the installer from `lib/<abi>/` inside the APK, labelled
`apk_data_file`, read-only. This is the only writable-at-install,
executable-at-runtime location available to an unprivileged app.

Two build settings are **mandatory**, not optimisations:

```kotlin
// app/build.gradle.kts and core/build.gradle.kts
packaging { jniLibs { useLegacyPackaging = true } }
```

```xml
<!-- AndroidManifest.xml -->
<application android:extractNativeLibs="true">
```

With the modern packaging the installer never writes the libraries to a
filesystem path — they are mapped in place out of the (compressed) APK, and a
file that does not exist on disk cannot be exec'd. Set either of these wrong and
the entire shell environment stops working, with no error that points at the
cause.

The installer only unpacks files matching `*.so`, so every bundled executable
ships as `lib<name>.so`:

```
jniLibs/arm64-v8a/libbusybox.so   ->  $PREFIX/bin/busybox
jniLibs/arm64-v8a/libgit.so       ->  $PREFIX/bin/git
```

**This path changes on every app update.** It contains install-specific hashes.
Nothing may cache it across versions; `Bootstrap` re-creates the whole symlink
farm whenever the version code or the directory changes, because otherwise every
link in `$PREFIX/bin` dangles after an update.

### 2. `/system/bin` and `/system/xbin`

Always available, no setup:

* `sh` — mksh. POSIX, not bash. No `[[ ]]`, no arrays, no process substitution.
* `toybox` — ~150 applets on a current device: `ls`, `cat`, `grep`, `sed`,
  `find`, `tar`, `env`, `ps`, `kill`, and so on.
* `linker64`, `app_process`, `dumpsys`, `getprop`, and friends.

This is the floor. With nothing bundled, the app still has a usable shell.

### 3. Shell scripts — always

This is the loophole worth internalising: when you run `sh script.sh`, the
**interpreter** is what gets exec'd, and the script is merely *read*. Reading a
file in the app's own data directory is perfectly allowed.

So scripts work everywhere, on both flavours, with no restrictions. Only
compiled binaries are affected.

## What does not work

| Attempt | Why not |
| --- | --- |
| Download a static binary and run it | Lands in the data directory. Refused. |
| `chmod 777` the binary | Irrelevant. Not a permissions problem. |
| `/data/local/tmp` | Owned by the `shell` user. An app cannot write there. |
| `proot` with a downloaded rootfs | proot itself can run from the APK, but the guest binaries it `execve`s still go through the kernel, and the kernel still refuses. proot intercepts path resolution, not SELinux. |
| `memfd_create` + `fexecve` | Anonymous executable memory is denied to `untrusted_app`. |
| Compile on device and run the output | Same refusal, and there is no compiler that could run anyway. |
| `sudo`, `su` | No root. No setuid binaries reachable. |
| `apt`, `apk`, `pkg`, `dnf`, `brew` | No system package manager and nowhere to install to. On `power` the app's own `pkg_install` takes their place; see below. |

`ShExecTool` intercepts `sudo`, `apt`, `systemctl` and friends *before*
spawning a process, and returns an explanation. Without that, a model sees "not
found", tries a variation, and burns three turns.

## Why there are two flavours

```
play   targetSdk 36   untrusted_app_3x   exec from data dir DENIED    publishable
power  targetSdk 28   untrusted_app_27   exec from data dir ALLOWED   sideload only
```

The `power` flavour is not a hack — it is what Termux relied on for years. Both
Android 15 and Android 16 refuse to install only `targetSdk < 24`, so a
28-targeting APK still installs in 2026. It just cannot go on Play.

`ExecPolicy` derives the tier from `applicationInfo.targetSdkVersion` rather
than a `BuildConfig` flag, so it stays correct if the flavour, the manifest and
the Gradle files ever disagree.

### Empirical check

Deriving the policy from the target SDK is correct in theory, but vendors ship
modified SELinux policies and the failure mode — every tool call returning
`EACCES` — is miserable to diagnose from a bug report. So `ExecPolicy.probe()`
actually runs `sh -c "echo apkcli-ok"` at startup and records the result in the
bootstrap stamp.

## The shebang problem

Android has no `/bin`, no `/usr/bin`, no `/etc`, no `/tmp`, no `/home`.

That breaks a large fraction of real scripts, and the kernel resolves a `#!`
line *before* any userspace code can intervene: `execve("script.sh")` makes the
kernel read `#!/bin/sh` and look for `/bin/sh`, which does not exist.
`ENOENT`, with nothing to hook.

`native/execshim` solves it the way `termux-exec` does — an `LD_PRELOAD` library
that interposes `execve`, `execv`, `execvp` and `execvpe`:

1. Linux-shaped absolute paths (`/bin/sh`, `/usr/bin/env`) are rewritten to
   `$PREFIX/bin/<name>`, then `nativeLibraryDir/lib<name>.so`, then
   `/system/bin/<name>`.
2. The shebang is read **by us** before the syscall, the interpreter resolved
   through the same rewrite, and the interpreter is exec'd with the script as
   its argument.

3. Termux's own prefix, `/data/data/com.termux/files/usr/...`, is mapped onto
   `$APKCLI_PREFIX/...` when the original does not exist — for installed
   packages whose binaries could not be relocated (see below).

Configured through the environment so it has no compiled-in paths:
`APKCLI_PREFIX`, `APKCLI_NATIVE_BIN`. If either is unset the shim forwards every
call untouched, so a misconfigured environment degrades to plain Android
behaviour rather than breaking exec entirely.

## Installing Termux packages (power)

Termux builds every package for `/data/data/com.termux/files/usr`, and that
string is everywhere: shebangs, `DT_RUNPATH` in every ELF file, OpenSSL's
certificate directory, Python's `subprocess` shell, the semaphore directory of
`libandroid-posix-semaphore`. `PackageManager` rewrites it while unpacking.

**The same-length alias.** `/data/data/com.termux/files/usr` is 31 characters,
and so is `/data/data/dev.apkcli.power/usr`. `/data/data/<package>` is the app's
own data directory, so a symlink `usr -> files/usr` there makes that path reach
the real `$PREFIX`. A same-length replacement can be applied to *every* file,
binaries included, without moving a single offset — the package then behaves
exactly as it does in Termux. The alias is checked by inode, not by canonical
path: with app data isolation (Android 11+) `/data/data/<pkg>` and
`/data/user/0/<pkg>` are separate mounts of one directory and never
canonicalise to the same string.

**Without the alias** (a longer package name such as the debug build's, or a
secondary user) text files get the real path, ELF files get their runpath
rewritten in place to `$ORIGIN/../lib` and similar — bionic expands `$ORIGIN`
since API 24, and the relative form is always shorter — and paths compiled into
binaries are left alone. `ExecPolicy` covers the ones that have an override
(`SSL_CERT_FILE`, `TERMINFO`, `GIT_EXEC_PATH`, the curl/git CA variables).

`$PREFIX/tmp` has no override and matters more than it looks: Termux's
`libandroid-posix-semaphore` (`.../usr/tmp/sem.*`) and `libandroid-shmem`
(`.../usr/tmp/ashv_key_*`) have it compiled in, and so does PostgreSQL's socket
directory. Android has no SysV IPC; these two libraries emulate it (shared
memory over ashmem, semaphores as files in `$PREFIX/tmp`) and are how Termux
runs PostgreSQL at all. When the path is unreachable, PostgreSQL takes the
failure for a name collision and tries the next name, forever: `initdb` spins at
"selecting default max_connections". So in this mode the longer paths below
the prefix still get same-length aliases where they fit — `$PREFIX/tmp` is 35
characters, and so is `/data/data/dev.apkcli.power.debug/t` (or
`/data/user/10/<pkg>/tmp_` for a secondary user); `bin`, `etc`, `lib`,
`libexec`, `share`, `include` and `var` get the same treatment — `bin` because
`pg_ctl` starts the server with `execl(".../usr/bin/sh")`, and with only `tmp`
aliased that failed with ENOENT. They are patched into binaries only where the Termux path is a
whole path component (followed by `/` or NUL). Text files keep the direct
path. A link already in the data directory that does not point into `files/`
is never touched.

**`LD_LIBRARY_PATH` must not contain `$PREFIX/lib`.** Bionic searches
`LD_LIBRARY_PATH` before `DT_RUNPATH`, for every process, `/system/bin`
included: toybox would load Termux's OpenSSL 3 `libcrypto.so` in place of the
system's BoringSSL. Packages find their libraries through their own runpath, as
in Termux.

The tool shell stays the farm's `sh` (system mksh): a package's `bash` or
`dash` in `$PREFIX/bin` is not picked up as the agent's shell, so upgrading or
removing it cannot take the shell tool down with it.

## PTYs do work

Worth stating because it is surprising: `/dev/ptmx` is reachable from an
untrusted app sandbox. A real master/slave pair needs no root.

`native/apkcli-core/src/pty.rs` opens it by hand rather than using bionic's
`openpty`, whose availability per API level is inconsistent across NDK
versions:

1. `open("/dev/ptmx", O_RDWR | O_CLOEXEC)`
2. `ioctl(TIOCSPTLCK, &0)` — unlock
3. `ioctl(TIOCGPTN, &n)` — slave is `/dev/pts/<n>`
4. `fork`, then in the child: `setsid`, `ioctl(TIOCSCTTY)`, `dup2` onto
   0/1/2, reset signal dispositions, `execve`

The ioctl numbers are identical on every Linux ABI targeted here.

Between `fork()` and `execve()` the child may only call async-signal-safe
functions. Every allocation — argv, envp, the resolved path — happens *before*
the fork for that reason. This is the riskiest code in the project; see
"Process isolation" in `ARCHITECTURE.md` for why it should eventually live in
its own process.

## Bundling a binary that actually runs

Three things have to be right, and each has its own failure mode:

**Linked against bionic, not glibc.** A binary built on a Linux desktop requests
`/lib/ld-linux-aarch64.so.1` as its ELF interpreter. Android binaries request
`/system/bin/linker64`. This is why `tools/fetch-prebuilts.ps1` pulls Termux
packages instead of building from source on a desktop.

```sh
readelf -l libgit.so | grep interpreter   # => /system/bin/linker64
```

**No leftover `DT_RUNPATH`.** Termux binaries carry a hardcoded runpath of
`/data/data/com.termux/files/usr/lib`, which does not exist on a non-Termux
device, and the linker tries it first.

Do **not** use `patchelf --remove-rpath`: it rebuilds the program header table
and can change the number and ordering of `PT_LOAD` segments, which Android 11+
rejects. `tools/zero-runpath.py` instead sets the `d_val` of the
`DT_RPATH`/`DT_RUNPATH` entries to 0 in place — pointing at the mandatory empty
string at index 0 of `.dynstr`. Nothing moves, every offset stays valid.

```sh
readelf -d libgit.so | grep -E 'RPATH|RUNPATH'   # => nothing, or empty
```

**16 KB page alignment.** Android 15+ devices may use 16 KB base pages, and from
2027-02-01 Play rejects updates whose native libraries are not aligned. NDK r28's
clang driver handles this for C/C++, but Rust drives the linker itself, so
`native/.cargo/config.toml` passes the flags explicitly:

```
-C link-arg=-Wl,-z,max-page-size=16384
-C link-arg=-Wl,-z,common-page-size=16384
```

`common-page-size` keeps the result compatible with 4 KB devices.

```sh
readelf -lW libapkcli_core.so | grep LOAD   # => Align 0x4000
```

## Sources

* [Termux and Android 10](https://github.com/termux/termux-packages/wiki/Termux-and-Android-10) — the original write-up of the W^X restriction and its consequences.
* [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes) — Android's own guidance and the Play deadline.
* [Android minimum targetSdk matrix](https://bayton.org/android/android-minimum-targetsdk-matrix/) — confirms Android 16 still refuses only `targetSdk < 24`, which is what keeps the `power` flavour installable.
* [Shipping QEMU in an APK](https://dev.to/ai2th/linxr-part-2-shipping-qemu-in-an-apk-4kij) — an independent account of the same `nativeLibraryDir` + `DT_RUNPATH` + segment-count problems.

Content from these sources was read and rephrased rather than reproduced.
