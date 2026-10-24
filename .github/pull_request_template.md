<!-- English or Russian, whichever you prefer. · На английском или русском — как удобнее. -->

## What and why · Что и зачем



## Checks · Проверки

- [ ] `python tools/audit.py`
- [ ] `python tools/check_strings.py`
- [ ] `./gradlew :core:testDebugUnitTest`
- [ ] `./gradlew :app:testPlayDebugUnitTest`
- [ ] `./gradlew :app:assemblePlayDebug`
- [ ] Rust changed: `cargo test` in `native/` · Менялся Rust: `cargo test` в `native/`
- [ ] Exec, the shell or packages changed: `./gradlew :app:assemblePowerDebug` and a run on a device or emulator · Менялись exec, оболочка или пакеты: `./gradlew :app:assemblePowerDebug` и запуск на устройстве или эмуляторе

## Where it ran · Где запускалось

<!-- Device or emulator, Android version, play or power. · Устройство или эмулятор, версия Android, play или power. -->

## Strings and docs · Строки и документация

- [ ] New UI strings are in `values/strings.xml` and `values-ru/strings.xml` · Новые строки интерфейса есть и в `values/strings.xml`, и в `values-ru/strings.xml`
- [ ] Docs are updated in both languages, or the PR says which one is missing · Документация обновлена на обоих языках, или в PR сказано, какой версии не хватает
- [ ] No keys, tokens or signing data in the diff · В изменениях нет ключей, токенов и данных подписи
