# Участие в разработке

[English](CONTRIBUTING.md) · **Русский**

Issues и pull request'ы приветствуются. Здесь — что проверить, прежде чем
открыть pull request.

## Перед началом

* Тулчейн перечислен в разделе [«Сборка»](README.ru.md#сборка) README;
  `./gradlew envReport` покажет, чего не хватает.
* Прежде чем менять что-либо в том, как запускаются процессы, прочтите
  [docs/ru/ANDROID_EXEC_NOTES.md](docs/ru/ANDROID_EXEC_NOTES.md). Запрет на
  exec определяет весь слой runtime.
* Для крупного изменения сначала откройте issue, чтобы договориться о подходе
  до того, как работа сделана.
* Все участники следуют [Кодексу поведения](CODE_OF_CONDUCT.ru.md).
* О проблеме безопасности сообщайте приватно, как описано в
  [SECURITY.ru.md](SECURITY.ru.md), а не в публичном issue.

## Проверки

Перед pull request'ом:

```sh
python tools/audit.py                 # статический аудит; ненулевой код выхода, если есть находки
python tools/check_strings.py         # все строки есть на английском и русском, с одинаковыми плейсхолдерами
./gradlew :core:testDebugUnitTest     # JVM-тесты :core
./gradlew :app:testPlayDebugUnitTest  # JVM-тесты :app
./gradlew :app:assemblePlayDebug      # сборка play, вместе с Rust
```

В зависимости от того, что изменилось:

| Что изменилось | Что ещё запустить |
| --- | --- |
| Rust в `native/` | `cargo test` в `native/` |
| exec, оболочка, пакеты | `./gradlew :app:assemblePowerDebug` и запуск на устройстве или эмуляторе |
| `tools/zero-runpath.py` | `python tools/test_zero_runpath.py` |
| `design/tokens.json` | `python tools/gen_theme.py` и `python tools/gen_design.py`, чтобы приложение и прототип менялись вместе |
| `design/prototype/` | `python tools/gen_design.py`, затем `python tools/check_design.py` (нужен Google Chrome; скрипт ищет его по пути установки в Windows) |

## Соглашения

* **Сгенерированные файлы не правят вручную.** `DesignTokens.kt` получается из
  `design/tokens.json` через `tools/gen_theme.py`; `Codicons.kt` и
  `SetiIcons.kt` — из `tools/gen_codicons.py` и `tools/gen_seti_icons.py`.
  Меняйте исходник и перегенерируйте.
* **Строки — на обоих языках.** Новая строка интерфейса добавляется сразу в
  `app/src/main/res/values/strings.xml` и `values-ru/strings.xml`. Названия
  режимов агента (Confirm, From Rules, Bypass Mode) на всех языках остаются
  английскими.
* **Документация — на обоих языках.** Изменение в `README.md` или в файле из
  `docs/` сопровождается правкой русской версии (`README.ru.md`, `docs/ru/`) в
  том же pull request'е. Если вы не пишете на одном из двух языков, укажите это
  в pull request'е — вторую версию можно обновить отдельно.
* **Никаких секретов в репозитории.** Ключи, токены и данные подписи не
  попадают в git: `local.properties`, `keystore.properties`, `*.keystore` и
  `token.txt` в `.gitignore`. В тестах — заведомо фальшивые ключи.

## Лицензия

Вклад принимается на условиях [Apache-2.0](LICENSE) (раздел 5 лицензии), как и
весь проект.
