# ApkCLI

**English** · [Русский](README.ru.md)

An AI coding agent that runs **on** an Android phone, not against it. Real
files, a real shell, a real editor — no root, no companion PC required.

Think Cursor or Claude Code, with the parts that assume a desktop replaced by
parts that work inside an Android app sandbox.

---

## What makes this different from a chat app with a code theme

| | |
| --- | --- |
| **The shell is real** | `sh -c` on the device, with a PTY so `vi`, `less` and progress bars behave. Bundled tools run from the APK's native library directory, which is the only exec-permitted location available without root. |
| **The environment is three directories** | `files/home`, `files/usr`, `files/var`. Not scattered across app-specific external dirs and cache directories the OS can evict mid-build. |
| **Heavy work is in Rust** | Workspace search (ripgrep's engine linked as a library), diffing, the conversation store (SQLite + FTS5), token accounting, PTY, CPU topology. |
| **Load is governed, not hoped for** | Work is admitted by class. Bulk work is pinned to the little cluster and niced down; builds are *declined* when the device is thermally throttled, with the reason surfaced to the user and the model. |
| **A million-token window is budgeted** | Compaction produces a structured digest — including what was already tried and failed — and the verbatim history stays indexed so the agent can search it. |
| **A connection is a key** | Anthropic, OpenAI, OpenRouter and Kiro are known schemes: paste the key and nothing else is asked. Any other OpenAI-compatible or Anthropic Messages server, hosted or on your LAN over plain HTTP (a key is never sent unencrypted to a public host), is a custom connection with its URL. No model id to type: the list and its metadata are read from the endpoint, never hardcoded. |
| **Every message is a checkpoint** | Restore above any message of yours takes the chat, the agent's context and the files it changed back to before it, and puts the message back in the composer. |
| **Search needs no key** | `web_search` reads DuckDuckGo's server-rendered lite page over HTTPS and parses it on the device — no WebView, no account. A Brave/Tavily/SearXNG endpoint is optional. |

---

## Status

Working end to end: configure an endpoint, start a session, the agent reads and
edits files, runs commands, and the context window is managed.

| Area | State |
| --- | --- |
| Rust core — store, search, diff, tokens, PTY, scheduler, JNI | complete |
| Exec policy, 3-directory environment, bootstrap, `LD_PRELOAD` shim | complete |
| Load governor — pools, affinity, thermal policy | complete |
| LLM layer — OpenAI-compatible, Anthropic, Kiro (Amazon Q, AWS event-stream); SSE, retry | complete; Kiro run against a live account on the emulator |
| Agent — system prompt, 38 tools (41 on power), loop, subagents, approvals | complete |
| Packages — Termux repository, dependency resolution, relocation (power) | complete |
| Context — budget, compaction, digest, FTS recall, project memory | complete |
| UI — the design prototype ported 1:1: black theme generated from `design/tokens.json`, home, projects, workspace, settings | complete |
| UI — agent chat (streaming, reasoning, tool cards, diffs, context meter), checkpoints, connections by scheme | complete |
| UI — workbench: explorer, editor (sora, lexical highlighting), resizable split with chat, agent change review and revert | complete |
| UI — English and Russian, switchable in Settings › Interface | complete |
| UI — terminal: several shells, the agent's background jobs listed beside them, colour, Ctrl-C and the keys an IME lacks | complete |
| Agent — environment context: open editor files, the active one, and every terminal with what is running in it | complete |
| UI — a project's chat history: search, rename, delete; find in the chat | complete |
| `:shell` process isolation for PTY | **not yet built** — see `docs/ARCHITECTURE.md` |
| Compiling build, Kotlin + Rust, debug and release | complete |
| Running on a device | checked on an API 34 emulator (x86_64) — see below |

### What the build proves

Everything compiles and packages, from a clean toolchain install:

| | |
| --- | --- |
| `:core` and `:app` Kotlin | no errors, no warnings |
| `:app:assemblePlayDebug` / `assemblePowerDebug` | 22.3 MB each, targetSdk 36 / 28 |
| `:app:assemblePlayRelease` / `assemblePowerRelease` | 10.5 MB each, R8 + resource shrinking + lint-vital, signed (v2) |
| Rust, all three ABIs | arm64-v8a, armeabi-v7a, x86_64 link |
| JNI symbols | 15/15 exported names match `Native.kt`, checked against the ELF |
| 16 KB page alignment | every `LOAD` segment at `0x4000` |
| `android:extractNativeLibs` | `true` in both merged manifests — the flag the whole exec design rests on |

Fifteen real defects came out of that first build, including two that only
appear on 32-bit (`RTLD_NEXT` has a different value on ILP32, where the 64-bit
constant silently means `RTLD_DEFAULT` and would recurse forever), a lexer trap
where a `/*` inside a comment swallowed 50 lines of `app/build.gradle.kts`, and
backup rules that lint rejected. They are listed in `docs/ARCHITECTURE.md`.

### Verified on an emulator, and not

Run on an API 34 x86_64 emulator (Pixel 6a image), power build:

* The shell runs, from `nativeLibraryDir` and `$PREFIX/bin`; Termux packages
  install with `pkg_install` and their programs execute (`untrusted_app_27`).
* The PTY: `/dev/ptmx`, `fork`/`execve`, an interactive shell in the terminal
  panel with the prompt from `$PREFIX/etc/profile`, colour output, Ctrl-C,
  history recall redrawing a prompt wider than the screen, the foreground
  program and working directory read back from the kernel.
* Network from the shell (and why toybox `nc` looks offline on an emulator).
* Tool commands stream their output into the chat card while they run; a
  command that leaves something in the background (`cmd &`) returns as soon
  as it finishes, with a note naming what was stopped; a timeout kills the
  command and everything it started; `web_search` finishes in seconds and has
  a hard limit, as every tool does.
* A running card shows how long it has run and the last lines of its
  terminal: commands print on a pseudo-terminal, so tools draw their progress
  as in a terminal window. A `printf '\r…'` loop showed its colours, stderr in
  order and one progress line counting up in place; `npm install` showed its
  spinner and ended with its summary. `pkg_install` shows the installer's own
  lines as it goes (`Downloaded 3/5 tree, 36 KB`, `Unpacking 1/5 oniguruma`).
  pip's progress bars were not tried.
* The chat stays at the bottom after switching tabs, and while a call runs
  with the keyboard up.
* PostgreSQL 18 from Termux, on both package names: `pkg_install postgresql`,
  `initdb`, `pg_ctl start`, a `psql` query, `pg_ctl stop`.
* File icons: VS Code's own Seti icon font and colours in the explorer (API 34).
* Diffs number old and new lines in two columns, as on GitHub (checked on an
  edit that replaced three lines with two).
* The live view under a running command scrolls back and stays put; the chat
  stays where the user scrolled to while an answer or its reasoning streams,
  and follows again once brought back to the end.
* Each chat's commands appear in the terminal panel as "Agent · …", with their
  output and exit, and Ctrl-C there interrupts the running one.
* A screenshot sent with no text reached the endpoint as one JPEG image part.
* Runner installed `python` without asking, with the old stored "ask" entries
  in its settings; switching from Engineer to Runner while a command ran let
  the next command run without a question.
* `pip install pydantic` installed pydantic 2.12.5 with a prebuilt
  pydantic-core 2.41.5 in 20 s, and both imported.
* `octocat/Hello-World` opened from GitHub as a project.
* Memory: facts saved by the agent, global and per project, appear in the next
  request's prompt and in the memory sheet.
* The redesigned UI, against a scripted local server: the connection sheets
  (a key-only form, the custom form's checks), an approval, the changes bar
  and the Files badge, and a checkpoint restore that deleted the file the
  agent had written, removed the messages after it and put the text back in
  the composer. The Files tree and editor, and the terminal's green prompt
  with `ls -la`.
* Kiro against a live account: chats, tool calls and the prompt-cache
  measurements in `docs/PROVIDERS.md`.

Still unproven:

* Any physical device, and `untrusted_app_3x` (the Play build) beyond start-up.
* The `LD_PRELOAD` shim's `#!/bin/sh` rewriting, specifically.
* Whether TextMate highlighting survives R8 — the release build logs
  `R8: org.joni.ast.QuantifierNode.<clinit>() does not type check and will be
  assumed to be unreachable`, from a transitive dependency of sora-editor's
  TextMate support. Harmless if that path is genuinely dead, a crash if not.
* Compaction on a real long session, and most of the agent loop's error paths.
* The `<environment-context>` block inside a live request. Its text is
  unit-tested; an in-app turn that sends it has not been recorded.
* Anthropic's adaptive thinking against the live API. It is unit-tested on its
  wire format only (`docs/PROVIDERS.md` has what it follows).

---

## Building

### Prerequisites

| Tool | Version | Notes |
| --- | --- | --- |
| JDK | 17 | AGP 9.1 requires 17 minimum; built with Temurin 17.0.20.1 |
| Gradle | 9.8.0 | via `./gradlew`; AGP 9.1.1 needs 9.3.1+ |
| Android SDK | platform 36, build-tools 36.0.0 | |
| Android NDK | 28.2.13676358 | AGP 9.1's default; needed for 16 KB page alignment |
| Rust | 1.85+ | built with 1.99.0. `rustup target add aarch64-linux-android x86_64-linux-android` |
| cargo-ndk | 4.1.2 | `cargo install cargo-ndk` |

On Windows without Visual Studio, install Rust with the GNU host so the
`build.rs` scripts have a host linker — `libsqlite3-sys` compiles SQLite from C:

```
rustup-init.exe -y --default-host x86_64-pc-windows-gnu --profile minimal
```

Check what is actually present:

```sh
./gradlew envReport
```

### Static audit

Three Python checks cover things a compiler will not tell you, or will tell you
only in a form that points at the wrong line. They need nothing but Python 3.

```sh
python tools/audit.py                   # exits non-zero on findings
python tools/check_nested_comments.py   # also run by audit.py
python tools/test_zero_runpath.py       # verifies the ELF patcher
```

`audit.py` checks what is invisible on re-reading: Kotlin imports of project
symbols nothing declares, `R.*` references with no matching resource, `libs.*`
aliases missing from the version catalog, Rust `mod` declarations with no file,
JNI symbol names that disagree between `ffi.rs` and `Native.kt`, manifest
`android:name` classes that do not exist, unbalanced brackets, unused imports,
and a short list of risk patterns. It understands Kotlin extension receivers and
Rust raw strings and lifetimes — getting those wrong produced 77 phantom errors
on the first run, and scanning `app/build/intermediates` later produced another
18, which is why all discovery goes through one filtered `walk()`.

`check_nested_comments.py` is the one that earns its keep. Kotlin and Rust both
**nest** `/* */`, so a glob in prose (`/system/bin/*`) opens a second comment
and everything up to the next `*/` disappears. In `app/build.gradle.kts` that
`*/` turned out to be inside the string `"**/*.so"` fifty lines later, and the
compiler reported 232 errors, none of them near the actual mistake.

`test_zero_runpath.py` builds synthetic 64- and 32-bit ELFs with `DT_RUNPATH`
and `DT_RPATH`, runs the patcher, and asserts the tags were zeroed, `DT_NEEDED`
was not touched, and the file size is unchanged. That last one is the whole
point of not using `patchelf`.

### Build

```sh
# arm64 only, which is the sane default in 2026
./gradlew :app:assemblePlayDebug

# UI-only iteration, skipping the Rust step entirely
./gradlew :app:assemblePlayDebug -Papkcli.buildRust=false
```

With `apkcli.buildRust=false` the app still runs: token estimation and search
fall back to Kotlin implementations, and the conversation store reports a clear
startup failure rather than crashing.

### The two flavours

```sh
./gradlew :app:assemblePlayDebug    # targetSdk 36 — publishable, exec restricted
./gradlew :app:assemblePowerDebug   # targetSdk 28 — sideload only, exec unrestricted
```

This is **the** decision in the project, not a packaging detail.

Since Android 10, SELinux denies `execve()` on any file inside an app's data
directory. Apps targeting API 28 or lower land in a domain that still permits it.

* **play** — can only execute binaries shipped inside the APK and those in
  `/system/bin`. No runtime package installation is possible, so the package
  tools are not offered and the system prompt says why.
* **power** — installs Termux packages (`python` with `pip`, `nodejs` with
  `npm`, `git`, `clang`, … ~3000) through `pkg_install`, `pkg_search` and
  `pkg_remove`, and runs them from `$PREFIX`. Still installable on Android 15
  and 16, which refuse only `targetSdk < 24`. Not publishable on Play.

Full explanation: `docs/ANDROID_EXEC_NOTES.md`.

### Packages (power)

There is no apt or dpkg: both have Termux's paths compiled in. The app reads
the repository's `Packages` index itself (checked against the SHA-256 in
`Release`), resolves the dependency closure, verifies every `.deb` against
the index, and unpacks it into `$PREFIX` with
`/data/data/com.termux/files/usr` rewritten. On the release build the
rewrite is to `/data/data/dev.apkcli.power/usr` — the same 31 characters, a
symlink to the real prefix — so even paths inside binaries can be patched in
place. A build whose package name is too long for that (the debug build), or a
secondary user, gets direct paths and `$ORIGIN` runpaths instead, plus
same-length aliases for the prefix's top-level directories where they fit
(`$PREFIX/tmp` → `/data/data/dev.apkcli.power.debug/t`, `bin` → `.../b`…).
`$PREFIX/tmp` is compiled into Termux's POSIX-semaphore and shared-memory
libraries; PostgreSQL, unable to reach it, does not fail but retries forever,
which is why `initdb` used to hang at "selecting default max_connections".

Not done: the repository's GPG signature is not verified (HTTPS and the
checksums are what protect the download), and Termux's maintainer scripts are
not run — for common packages they only print notes or pre-compile Python.
Installed files are tracked in `$PREFIX/var/lib/apkcli/packages.json`.

pip gets `~/.config/pip/pip.conf` on first start, once: the [Termux User
Repository](https://termux-user-repository.github.io/pypi/)'s index of
prebuilt Android wheels as an extra index, and `only-binary` for the Rust
packages it covers (pydantic-core, tokenizers, tiktoken, watchfiles), which
otherwise compile for an hour or time out. It is a community index; delete
the file to install from PyPI alone.

### Bundling command-line tools

The app works with nothing bundled — it falls back to `/system/bin/sh` (mksh)
plus toybox, which covers ~150 applets on a current device. To add real tools:

```sh
pwsh tools/fetch-prebuilts.ps1 -Abi arm64-v8a -Packages busybox,git
```

They must be bionic-linked, which is why the script pulls Termux packages rather
than building from a Linux desktop. See `native/prebuilt/README.md`.

---

## First run

1. **Settings → Connections.** The list starts empty. Add Anthropic, OpenAI,
   OpenRouter or Kiro: one field, the key.
   Kiro takes what Kiro Account Manager takes: the account's `refreshToken`
   from the Kiro IDE (`~/.aws/sso/cache/kiro-auth-token.json`) for a Google or
   GitHub sign-in, and for Builder ID the same with its `clientId` and
   `clientSecret`, as JSON or in the manager's export format. Exchanging it may
   sign the IDE out, and the form says so. "Custom" is for a local server or a proxy and
   asks for its URL and format. The connection with a key becomes active if the
   active one has none.
2. **Models.** No model id is ever required. Saving a key checks it and fetches
   the model list, a default is chosen, and the model sheet switches between
   them; if the list is still empty when a message is sent, it is fetched
   then. Discovery reads the listing shapes of OpenRouter, OpenAI, Anthropic
   (paged), Kiro, vLLM, LM Studio, llama.cpp, Ollama, Together (bare array),
   Groq, Mistral, Fireworks, DeepInfra, Gemini and LiteLLM, and drops what the
   endpoint marks as image, speech, embedding or search models. OpenRouter,
   Anthropic and Kiro report context windows in the list; a window that is
   still a guess shows as "≈" in the model sheet, and the first overflow error
   teaches the app the real number.
3. **Projects.** Home opens a project, creates one, or clones a public GitHub
   repository (its files, no git history); the project list also copies in a
   folder from the device. Projects live in `~/projects/<name>`.
4. **Workspace.** Three tabs: Agent, Files and Terminal; on a tablet or in
   landscape the agent shares the screen with one of the other two. The
   composer attaches photos and screenshots, and the book icon shows what the
   agent remembers and edits it. Above each of your messages, Checkpoint ·
   Restore takes the chat, the context and the agent's file changes back to
   before it.

### Agent modes

The mode decides when the agent asks for permission. Reads, searches and
listings never ask. The names are the same in every language.

| Mode | Asks | For |
| --- | --- | --- |
| Confirm | Before every change: a file written, moved or deleted, a command, a package, a subagent. | Default. You approve changes as they come. |
| From Rules | Only about what is not ticked in Settings › Rules. A fresh install ticks file edits and subagents. | Day-to-day work: what you trust goes ahead, the rest waits. |
| Bypass Mode | Nothing, deletions included. | Working a task to the end. File edits can be reverted; a deleted folder cannot. |

Switching modes applies to the running turn. Subagents investigate with a
read-only Explorer. Settings from builds that had Engineer and Runner carry
over: Engineer becomes Confirm, Runner becomes Bypass Mode.

---

## Layout

```
app/                      Compose UI
core/                     Everything else
  agent/                  Prompt, tools, loop, compaction
  llm/                    Endpoints, protocols, streaming
  runtime/                Environment, exec policy, shell, PTY
  sched/                  Load governor
  store/                  Conversation store, settings, secrets
  web/                    Fetch and HTML extraction
native/
  apkcli-core/            Rust: store, search, diff, tokens, PTY, sched
  execshim/               LD_PRELOAD shim for shebangs and /bin paths
  prebuilt/               Staged bionic binaries (fetched, not committed)
docs/
tools/
```

## Documentation

* [docs/ANDROID_EXEC_NOTES.md](docs/ANDROID_EXEC_NOTES.md) — the exec
  restriction, in detail. Read this before changing anything about how
  processes start.
* [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — why the pieces are shaped the
  way they are.
* [docs/PROVIDERS.md](docs/PROVIDERS.md) — the connection schemes: base URLs,
  where the credential goes, what the model lists report, with a source for
  every claim.
* [docs/TOOLCHAIN.md](docs/TOOLCHAIN.md) — where each pinned version came from.
* [CONTRIBUTING.md](CONTRIBUTING.md) — the checks to run before a pull request.

The documents are also in Russian: [README.ru.md](README.ru.md),
[docs/ru/](docs/ru/), [CONTRIBUTING.ru.md](CONTRIBUTING.ru.md).

## Licence

[Apache-2.0](LICENSE). The bundled fonts and icons keep their own licences,
in `app/src/main/assets/licenses/`.
