# Toolchain and version pins

**English** · [Русский](ru/TOOLCHAIN.md)

Every version in `gradle/libs.versions.toml` and the Cargo manifests was chosen
in **October 2026** from the sources below, and **all of them now resolve**: the
whole project has been built from a clean toolchain install.

Verified on Windows, October 2026:

| Step | Result |
| --- | --- |
| `:core:compileDebugKotlin` | clean, no warnings |
| `:app:compilePlayDebugKotlin` | clean, no warnings |
| `:app:assemblePlayDebug` | APK, 22.3 MB (arm64-v8a + x86_64, with the Rust core) |
| `:app:assemblePowerDebug` | APK, 22.3 MB, targetSdk 28 |
| `:app:assemblePlayRelease` / `assemblePowerRelease` | APK, 10.5 MB each (Inter and JetBrains Mono bundled), R8 + resource shrinking + lint-vital, v2-signed |
| `cargo ndk` arm64-v8a / armeabi-v7a / x86_64 | all three link |
| JNI symbol parity | 15/15 against `Native.kt` |
| 16 KB page alignment | all LOAD segments `0x4000` |

Build the two release variants one at a time (or with `--max-workers=4`). Run
together, both R8 passes compete inside the 4 GB daemon heap and the build
stalls in `minifyPlayReleaseWithR8`.

### Release signing

Release builds are signed only when four properties are set, and never from the
repository — put them in `~/.gradle/gradle.properties` (or `GRADLE_USER_HOME`):

```
apkcli.storeFile=/absolute/path/to/release.jks
apkcli.storePassword=…
apkcli.keyAlias=…
apkcli.keyPassword=…
```

Without them the release APK is unsigned and the build says so. Android refuses
an unsigned APK with "the package appears to be invalid", which looks like a
corrupt download rather than a missing signature.

### Icons

`app/src/main/kotlin/dev/apkcli/ui/icons/Codicons.kt` is generated from
[microsoft/vscode-codicons](https://github.com/microsoft/vscode-codicons) by
`tools/gen_codicons.py`, from the npm package `@vscode/codicons` pinned to
0.0.46-24 by version and SHA-512 in the generator's docstring. The icons are
CC BY 4.0; the attribution is in the generated file and ships in the APK as
`assets/licenses/codicons.txt`.
The generator refuses SVG features it does not translate (transforms, opacity,
literal fills, non-square viewBoxes) rather than silently drawing a wrong
glyph — which is why `Github` is the package's `github-inverted`: `github.svg`
has a 24×25 viewBox. Every icon in the app is one of these; the redesign added
`location`, the pin on the checkpoint row above each message.

The file-type icons in the explorer, editor tabs and the changes list are VS
Code's own: its default "Seti" icon theme, which is a font. Nothing is
converted or redrawn. `tools/gen_seti_icons.py` takes, from microsoft/vscode at
a pinned commit, `extensions/theme-seti/icons/seti.woff` and
`vs-seti-icon-theme.json`, plus each built-in extension's `package.json` for
the extensions and file names its languages claim:

```
python tools/gen_seti_icons.py
```

It writes `app/src/main/res/font/seti.ttf` (the WOFF's tables inflated and
copied into a TrueType file, which Android can load; WOFF is only a
compressed wrapper) and `ui/icons/SetiIcons.kt`: the glyph and colour for each
file name and extension, dark and light, resolved as VS Code resolves them —
the name, then the extensions longest first (`spec.ts` before `ts`), then the
language VS Code would give the file, then the default. The icon is the
glyph drawn in the theme's colour at 1.22 times the icon box, as VS Code sets
it (150% of 13px in a 16px box). Folders get no icon, as with Seti in VS Code.

Seti UI is MIT, © 2014 Jesse Weed; the notice ships in the APK as
`assets/licenses/seti-ui.txt`. The previous icons (Material Icon Theme's SVGs converted to vectors) are gone.

### Design prototype

`design/tokens.json` holds the black theme: colours, text styles, radii, sizes
in dp and motion. It is the one place those numbers live: the prototype reads
it, and `tools/gen_theme.py` generates the Compose theme from it.

```
python tools/gen_theme.py
python tools/gen_design.py --out %USERPROFILE%\Desktop\ApkCLI-design.html
python tools/check_design.py
pwsh -File tools/shoot_design.ps1 [-Width 360 -Height 800]
python tools/check_strings.py [--unused]
```

`gen_theme.py` writes `ui/theme/DesignTokens.kt` (colours, text styles,
radii, sizes, motion; never edited by hand) and copies Inter 4.1 and JetBrains
Mono 2.304 into `res/font`, with their OFL notices in `assets/licenses`. They
are the same files the prototype embeds, from the same pinned zips, so a value
changed in `tokens.json` moves the prototype and the app together once both
generators have run.

`gen_design.py` builds `design/prototype/{index.html,app.css,app.js}` into one
self-contained file in `build/design`: no server, no network, openable on a
phone. It takes the icon paths out of `Codicons.kt`, the glyph tables out of
`SetiIcons.kt`, the font out of `res/font/seti.ttf` and the logo out of
`ic_launcher_foreground.xml`, so the prototype cannot drift from the app; it
fails the build if a screen asks for an icon the app does not have. Inter 4.1
and JetBrains Mono 2.304 come from their releases, pinned by SHA-256 and cached
in `build/design-cache`, and are embedded as data URIs because Chrome will not
fetch a font over `file://`.

`gen_catalogue.py` prints the prototype's model lists from
[models.dev](https://models.dev/api.json), the open model database OpenCode
builds its own provider catalogue from. Its fields are the ones OpenCode's
provider config carries — model id, display name, `limit.context`,
`limit.output`, and the `attachment` / `reasoning` / `tool_call` flags — which
map onto the app's `ModelSpec`. The output is pasted into `app.js` as
`CATALOGUE`: sample data for the screens, since the app asks each endpoint for
its own list. Selection is by rule — newest N that call tools and return
text — so no model version is written down anywhere and `--refresh` moves the
lists on by itself. The custom connection form follows the same shape as
OpenCode's `provider.<id>.options`: `baseURL`, `apiKey`, `headers`; the known
services ask for the key alone.

The schemes those connections use — base URLs, where the credential goes, how
reasoning is expressed, what `/models` does and does not report, and what Kiro
needs — are in [PROVIDERS.md](PROVIDERS.md), with a source for every claim.

`check_design.py` loads the result in headless Chrome, visits every route and
clicks every control (231 of them), and reports any JavaScript error, so a
typo in an action does not sit there unnoticed.

`shoot_design.ps1` screenshots each route, sixteen of them from `01-home` to
`16-checkpoint`. Both pass `?shot`, which pins the screen to the token size:
headless Chrome here lays out at 504 CSS px whatever `--window-size` says, and
captures a different width again, so an unpinned capture is never the size it
claims.

`check_strings.py` compares the strings the code references with those
defined in `values/` (English) and `values-ru/` (Russian): what is missing in
either, whose `%1$s` placeholders differ between the two, and with `--unused`
what nothing references any more.

What that does **not** cover is a physical device: runtime behaviour has been
checked on API 34 and API 29 x86_64 emulators only. See the README.

If a build fails at resolution, this is where to look first.

## AGP 9 uses built-in Kotlin

This is the one piece of setup that is not a version number, and it is the first
thing that will bite anyone regenerating the build files.

AGP 9 provides Kotlin itself. Applying `org.jetbrains.kotlin.android` is a hard
error:

```
The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin
support since AGP 9.0
```

Two escape hatches look tempting and both are dead ends:

* `android.builtInKotlin=false` — still fails, because `android.newDsl=true` is
  *also* an AGP 9 default and the legacy Kotlin plugin needs the old
  `BaseExtension`, producing
  `ApplicationExtensionImpl$AgpDecorated_Decorated cannot be cast to ... BaseExtension`.
* `android.newDsl=false` — AGP's own docs describe this as a temporary bypass.

So: no `kotlin.android` plugin anywhere, and the Kotlin *compiler* version is
raised from AGP's pinned 2.2.10 to the catalog's 2.3.20 with a buildscript
classpath entry in the root build file. The Compose compiler ships with Kotlin
and must match it exactly, which is why accepting AGP's pin is not an option.

## The one pin that is not free to change

```
compose = "1.11.4"        -> requires compileSdk 36
composeMaterial3 = "1.4.0"
```

Compose 1.12 (and therefore BOM `2026.08.00` and later) **requires
`compileSdk = 37` and AGP 9.1.2+**. Bumping `compose` past 1.11.x without also
bumping `compileSdk` produces an AGP error about a dependency requiring a newer
compile SDK.

The BOM is deliberately not used. `compose-bom:2026.09.00` resolves to 1.12.x,
which would pull compileSdk 37 in as a side effect of what looks like a routine
version bump. Explicit pins make the coupling visible.

To move to Compose 1.12: set `compileSdk = 37`, `agp = 9.1.2` or later, install
the android-37 platform, then bump `compose`.

## Build tools

| | Pinned | Built with | Source |
| --- | --- | --- | --- |
| AGP | 9.1.1 | 9.1.1 | AGP 9.1.1 release notes, April 2026 |
| Gradle | 9.8.0 (wrapper) | 9.8.0 | AGP 9.1.1 requires 9.3.1+; 9.8.0 was current |
| JDK | 17 | Temurin 17.0.20.1 | AGP 9.1.1's minimum |
| Kotlin | 2.3.20 | 2.3.20 | kotlinlang.org "What's new in Kotlin 2.3.20" |
| compileSdk | 36 | android-36 | AGP 9.1.1 supports up to 37 |
| Build tools | 36.0.0 | 36.0.0 | AGP 9.1.1's default |
| NDK | 28.2.13676358 | r28c | AGP 9.1.1's default |
| Rust | 1.85+ (`rust-version`) | 1.99.0 | current stable |
| cargo-ndk | — | 4.1.2 | `cargo install cargo-ndk` |

The NDK is pinned to AGP's *own* default on purpose, so AGP's stripping tasks
and the cargo-ndk cross-compile use one toolchain rather than silently pulling a
second ~1 GB copy.

On Windows without Visual Studio, install Rust with the GNU host — it brings its
own linker, so the `build.rs` scripts (notably `libsqlite3-sys`, which compiles
SQLite from C) can link for the host:

```
rustup-init.exe -y --default-host x86_64-pc-windows-gnu --profile minimal
```

AGP 9.1.1 supports API 37 and below, so compileSdk 37 is reachable without an
AGP change — only Compose forces the pair to move together.

### targetSdk

```
targetSdkPlay  = 36    Play requires 36+ for submissions from 2026-08-31
targetSdkPower = 28    the last level where exec from the data dir is permitted
```

Android 16 did not raise the minimum `targetSdk` for on-device installation; it
is still 24, same as Android 15. That is what keeps the `power` flavour
installable. See `ANDROID_EXEC_NOTES.md`.

## Libraries

All of these resolved on the first successful build; the "confidence" column
that used to be here is gone because it is no longer guesswork.

| | Pinned |
| --- | --- |
| compose (core) | 1.11.4 |
| compose-material3 | 1.4.0 |
| androidx-core-ktx | 1.16.0 |
| androidx-activity-compose | 1.10.1 |
| androidx-lifecycle | 2.9.0 |
| androidx-datastore | 1.1.1 |
| okhttp | 5.1.0 |
| kotlinx-serialization-json | 1.9.0 |
| kotlinx-coroutines | 1.10.2 |
| sora-editor | 0.23.6 |
| xz (org.tukaani) | 1.12 |
| junit | 4.13.2, `:core` JVM tests only |

`material-icons-core` is gone: every icon is a codicon from `Codicons.kt` (see
Icons above), as the prototype draws them. The Material set was frozen at
1.7.8 anyway.

JUnit 4.13.2 is JUnit 4's final release and what AGP's unit-test runner takes
without extra engines. `./gradlew :core:testDebugUnitTest` runs the JVM tests
(the Kiro wire format and credentials, AWS event-stream frames, connection
schemes, Anthropic listings); the Rust store has its own under `cargo test`.

### OkHttp 5 nullability

`Response.body` is **non-null** in OkHttp 5 (it was `ResponseBody?` in 4). Code
written against the 4.x shape compiles with warnings and carries dead fallback
branches: `response.body?.string() ?: ""` can never take the elvis arm. The
adapters and `WebFetcher` were corrected during the first build.

### Deliberate omissions

| Not used | Instead | Why |
| --- | --- | --- |
| Room | SQLite in the Rust core | No KSP, so no processor version to keep aligned with every Kotlin bump — and the store needs to be reachable from native code anyway |
| Hilt / Koin | `AgentRuntime` + `ServiceLocator` | The graph is one object with a dozen eager members |
| Navigation | `Navigator` in `ui/nav` | Four destinations; the workspace's tabs are not destinations |
| jsoup | `HtmlExtractor` | ~600 KB for a job with no DOM queries in it |
| security-crypto | Keystore directly in `SecretStore` | Deprecated; the API underneath is stable and this is one file |
| EventSource | `Sse` | Reconnection is actively harmful mid-completion |
| Material icons | Codicons | VS Code's own glyphs, as the prototype draws them |
| apt / dpkg, Commons Compress | `core/pkg` (own ar/tar reader, xz for Java) | apt and dpkg have Termux's paths compiled in; a `.deb` needs a sliver of ar and tar |

## Rust

| | Pinned | Notes |
| --- | --- | --- |
| Rust | 1.85+ (`rust-version`) | built with 1.99.0 |
| jni | 0.21 | |
| rusqlite | 0.32, `bundled` | resolved to 0.32.1 / libsqlite3-sys 0.30.1. `bundled` compiles SQLite from C through the NDK clang, so this is the dependency that makes the NDK mandatory rather than optional |
| grep-searcher / grep-regex / grep-matcher | 0.1 | Stable API |
| ignore / globset | 0.4 | |
| similar | 2 | |
| blake3, memchr, once_cell, parking_lot, regex | current majors | |
| android_logger | 0.14 | Android target only |

Two settings in `native/Cargo.toml` are load-bearing:

```toml
panic = "unwind"   # the JNI layer catches panics; "abort" would kill the app
```

and in `native/.cargo/config.toml`:

```
-C link-arg=-Wl,-z,max-page-size=16384
-C link-arg=-Wl,-z,common-page-size=16384
```

16 KB page alignment is required for Play from 2027-02-01. NDK r28's clang
driver does this for C/C++, but Rust drives the linker itself. Confirmed on the
built libraries — every `LOAD` segment reports alignment `0x4000`:

```sh
llvm-readelf -l core/src/main/jniLibs/arm64-v8a/libapkcli_core.so
```

### Two Android-only bindings the `libc` crate does not provide

Both are in `execshim` and both were compile errors on the first cross-compile:

* **`RTLD_NEXT`** is not bound for Android, and bionic's value depends on
  pointer width: `(void*)-1` on LP64, `0xfffffffe` on ILP32. These are not
  interchangeable — on 32-bit, `-1` *is* `RTLD_DEFAULT`, so using the 64-bit
  constant there would make `dlsym` resolve `execve` from the global scope,
  find the interposer itself, and recurse until the stack died. `rtld_next()`
  splits on `target_pointer_width` for exactly this reason.
* **`environ`** is exported by bionic and declared in its `<unistd.h>`, but
  `libc::environ` exists only for glibc-like targets, so it is declared in an
  `extern "C"` block.

## Checking what is installed

```sh
./gradlew envReport
rustup target list --installed
```

`envReport` prints the JDK, `ANDROID_HOME`, the NDK path, and where `cargo` and
`cargo-ndk` were found, so a failing native step is diagnosable without reading
Gradle's output.

## If resolution fails

1. Find the real latest version on
   [Google's Maven index](https://maven.google.com/web/index.html) or
   [Maven Central](https://central.sonatype.com).
2. Bump it in `gradle/libs.versions.toml`.
3. For anything in the Compose family, re-read the first section of this file
   before bumping — the `compose` ↔ `compileSdk` coupling is the one that will
   waste an afternoon.

## Sources

* [Android Gradle plugin 9.1.1 release notes](https://developer.android.com/build/releases/agp-9-1-0-release-notes) — AGP, Gradle, JDK, NDK, SDK build-tools
* [Jetpack Compose August '26 release](https://developer.android.com/blog/posts/what-s-new-in-the-jetpack-compose-august-26-release) — the Compose 1.12 / compileSdk 37 / AGP 9.1.2 requirement
* [BOM to library version mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping) — Compose core versions
* [Compose Material 3 releases](https://developer.android.com/jetpack/androidx/releases/compose-material3) — material3 1.4.0 stable
* [Play target API level requirements](https://developer.android.com/google/play/requirements/target-sdk) — the 2026-08-31 targetSdk 36 deadline
* [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes) — alignment requirement and the 2027-02-01 deadline
* [Android minimum targetSdk matrix](https://bayton.org/android/android-minimum-targetsdk-matrix/) — Android 16 keeps the install minimum at 24
* [What's new in Kotlin 2.3.20](https://kotlinlang.org/docs/whatsnew2320.html)
* [sora-editor on Maven Central](https://central.sonatype.com/artifact/io.github.Rosemoe.sora-editor/editor)
* [OpenRouter `/api/v1/models`](https://openrouter.ai/api/v1/models) — queried live for the metadata fields `ModelDiscovery` reads

Content from these sources was read and rephrased rather than reproduced.
