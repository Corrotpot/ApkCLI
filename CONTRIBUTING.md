# Contributing

**English** · [Русский](CONTRIBUTING.ru.md)

Issues and pull requests are welcome. This page is what to check before
opening one.

## Before you start

* The toolchain is listed in the README's [Building](README.md#building)
  section; `./gradlew envReport` shows what is missing.
* Read [docs/ANDROID_EXEC_NOTES.md](docs/ANDROID_EXEC_NOTES.md) before changing
  anything about how processes start. The exec restriction shapes the whole
  runtime layer.
* For a large change, open an issue first, so the approach is agreed on before
  the work is done.
* Everyone taking part follows the [Code of Conduct](CODE_OF_CONDUCT.md).
* A security problem is reported privately, as [SECURITY.md](SECURITY.md)
  describes, not in a public issue.

## Checks

Before a pull request:

```sh
python tools/audit.py                 # static audit; exits non-zero on findings
python tools/check_strings.py         # every string in English and Russian, same placeholders
./gradlew :core:testDebugUnitTest     # JVM tests of :core
./gradlew :app:testPlayDebugUnitTest  # JVM tests of :app
./gradlew :app:assemblePlayDebug      # the play build, Rust included
```

Depending on what changed:

| Changed | Also run |
| --- | --- |
| Rust in `native/` | `cargo test` in `native/` |
| Exec, the shell, packages | `./gradlew :app:assemblePowerDebug`, and a run on a device or an emulator |
| `tools/zero-runpath.py` | `python tools/test_zero_runpath.py` |
| `design/tokens.json` | `python tools/gen_theme.py` and `python tools/gen_design.py`, so the app and the prototype move together |
| `design/prototype/` | `python tools/gen_design.py`, then `python tools/check_design.py` (needs Google Chrome; the script looks for it at the Windows install path) |

## Conventions

* **Generated files are not edited by hand.** `DesignTokens.kt` comes from
  `design/tokens.json` through `tools/gen_theme.py`; `Codicons.kt` and
  `SetiIcons.kt` come from `tools/gen_codicons.py` and
  `tools/gen_seti_icons.py`. Change the source and regenerate.
* **Strings in both languages.** A new user-visible string goes into
  `app/src/main/res/values/strings.xml` and `values-ru/strings.xml` together.
  The agent mode names (Confirm, From Rules, Bypass Mode) stay in English in
  every language.
* **Documentation in both languages.** A change to `README.md` or to a file in
  `docs/` updates its Russian counterpart (`README.ru.md`, `docs/ru/`) in the
  same pull request. If you do not write one of the two languages, say so in
  the pull request, and the other version can follow separately.
* **No secrets in the repository.** Keys, tokens and signing data stay out of
  git: `local.properties`, `keystore.properties`, `*.keystore` and `token.txt`
  are ignored. Tests use obviously fake keys.

## Licence

Contributions are accepted under [Apache-2.0](LICENSE) (section 5 of the
licence), like the rest of the project.
