# Prebuilt device binaries

**English** · [Русский](README.ru.md)

This directory holds the extra command-line tools shipped inside the APK. It is
empty in a fresh clone — the payloads are fetched by
`tools/fetch-prebuilts.ps1` (Windows, PowerShell).

## Why they cannot simply be downloaded at runtime

On `targetSdk >= 29` the app's data directory carries the SELinux label
`app_data_file`, which does not grant `execute_no_trans`. A binary written there
at runtime cannot be executed, whatever its mode bits say. The only writable-at-
install, executable-at-runtime location available to a non-root app is
`nativeLibraryDir`, which the installer populates from `lib/<abi>/` inside the
APK.

Consequence: every executable has to be **in the APK at build time**, and has to
be named `lib<something>.so`, because the installer only unpacks files matching
that pattern.

## Naming convention

```
native/prebuilt/arm64-v8a/libgit.so      -> $PREFIX/bin/git
native/prebuilt/arm64-v8a/librg.so       -> $PREFIX/bin/rg
native/prebuilt/arm64-v8a/libbusybox.so  -> $PREFIX/bin/{sh,ls,sed,awk,...}
```

`Bootstrap.kt` creates the `$PREFIX/bin/*` symlinks pointing into
`nativeLibraryDir` on first run and re-creates them whenever the app's version
code changes, because an app update moves `nativeLibraryDir` to a new path.

## Where the binaries come from

Termux's package repository, because those builds already link against
**bionic** rather than glibc. A binary built on a Linux desktop requests
`/lib/ld-linux-aarch64.so.1` as its ELF interpreter, which does not exist on
Android; Android binaries request `/system/bin/linker64`.

The fetch script pulls `.deb` packages from `packages.termux.dev`, extracts
`data.tar.xz`, and renames the binaries. It then has to fix one more thing:

Termux binaries carry a hardcoded `DT_RUNPATH` of
`/data/data/com.termux/files/usr/lib`. On a non-Termux device that directory
does not exist, and the linker tries it first. The fix is to zero the `d_val`
field of the `DT_RPATH` / `DT_RUNPATH` dynamic entries **in place**. Do not use
`patchelf --remove-rpath`: it rewrites `PT_LOAD` segment layout, and Android 11+
rejects ELFs with an unexpected segment count.

## Minimum viable set

The app works with none of this present — it falls back to `/system/bin/sh`
(mksh) plus `/system/bin/toybox`, which covers roughly 150 coreutils applets on
a modern device. The bundled set is what turns it from "a shell" into "a dev
environment":

| Binary | Size (arm64) | Why |
| --- | --- | --- |
| `libbusybox.so` | ~1.1 MB | Full `ash`, real `sed`/`awk`/`grep`/`tar`, `vi` |
| `libgit.so` | ~4.5 MB | Version control. Needs `libz`, `libcurl`, `libpcre2` alongside |
| `librg.so` | ~4.8 MB | Optional: the Rust core already links ripgrep's engine |
| `libpython.so` | ~25 MB | Only worth it if you need Python; pulls a large stdlib payload |

Keep an eye on APK size: these are per-ABI. Shipping arm64 only is the sane
default in 2026.

## Checking a payload before trusting it

```sh
# ELF interpreter must be the Android linker
readelf -l libgit.so | grep interpreter      # => /system/bin/linker64

# No leftover Termux RUNPATH
readelf -d libgit.so | grep -E 'RPATH|RUNPATH'   # => nothing, or empty value

# 16 KB aligned (required by Play from 2027-02-01)
readelf -lW libgit.so | grep LOAD            # => Align 0x4000
```
