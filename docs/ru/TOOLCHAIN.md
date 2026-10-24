# Тулчейн и закреплённые версии

[English](../TOOLCHAIN.md) · **Русский**

Все версии в `gradle/libs.versions.toml` и манифестах Cargo выбраны
в **октябре 2026 года** по источникам, перечисленным ниже, и **все они теперь
разрешаются**: весь проект собран на чистой установке тулчейна.

Проверено на Windows, октябрь 2026 года:

| Шаг | Результат |
| --- | --- |
| `:core:compileDebugKotlin` | чисто, без предупреждений |
| `:app:compilePlayDebugKotlin` | чисто, без предупреждений |
| `:app:assemblePlayDebug` | APK, 22,3 МБ (arm64-v8a + x86_64, с ядром на Rust) |
| `:app:assemblePowerDebug` | APK, 22,3 МБ, targetSdk 28 |
| `:app:assemblePlayRelease` / `assemblePowerRelease` | APK, по 10,5 МБ (со встроенными Inter и JetBrains Mono), R8 + сжатие ресурсов + lint-vital, подпись v2 |
| `cargo ndk` arm64-v8a / armeabi-v7a / x86_64 | все три линкуются |
| Совпадение JNI-символов | 15/15 с `Native.kt` |
| Выравнивание страниц 16 КБ | все сегменты LOAD — `0x4000` |

Собирайте два релизных варианта по очереди (или с `--max-workers=4`). Если
запустить их вместе, оба прохода R8 конкурируют за 4 ГБ кучи демона, и сборка
зависает на `minifyPlayReleaseWithR8`.

### Подпись релизных сборок

Релизные сборки подписываются, только если заданы четыре свойства, и их
никогда не берут из репозитория — положите их в `~/.gradle/gradle.properties`
(или в `GRADLE_USER_HOME`):

```
apkcli.storeFile=/absolute/path/to/release.jks
apkcli.storePassword=…
apkcli.keyAlias=…
apkcli.keyPassword=…
```

Без них релизный APK остаётся неподписанным, и сборка об этом сообщает. Android
отказывается ставить неподписанный APK с сообщением
«the package appears to be invalid», что выглядит как повреждённая загрузка,
а не как отсутствие подписи.

### Иконки

`app/src/main/kotlin/dev/apkcli/ui/icons/Codicons.kt` генерируется из
[microsoft/vscode-codicons](https://github.com/microsoft/vscode-codicons)
скриптом `tools/gen_codicons.py` из npm-пакета `@vscode/codicons`, который
закреплён в docstring генератора на 0.0.46-24 по версии и по SHA-512. Иконки
распространяются под лицензией CC BY 4.0; атрибуция есть в сгенерированном
файле и попадает в APK как `assets/licenses/codicons.txt`.
Генератор отвергает конструкции SVG, которые не умеет преобразовать
(трансформации, прозрачность, явно заданные заливки, неквадратные viewBox),
вместо того чтобы молча нарисовать неправильный глиф, — поэтому `Github`
взят из пакета как `github-inverted`: у `github.svg` viewBox 24×25. Все
иконки в приложении — из этого набора; при редизайне добавилась `location` —
метка в строке чекпоинта над каждым сообщением.

Иконки типов файлов в Проводнике, на вкладках редактора и в списке изменений —
те же, что в самом VS Code: его стандартная тема иконок «Seti», а она
представляет собой шрифт. Ничего не конвертируется и не перерисовывается.
`tools/gen_seti_icons.py` берёт из microsoft/vscode на закреплённом коммите
`extensions/theme-seti/icons/seti.woff` и `vs-seti-icon-theme.json`, а также
`package.json` каждого встроенного расширения — ради расширений и имён файлов,
которые заявлены для его языков:

```
python tools/gen_seti_icons.py
```

Он записывает `app/src/main/res/font/seti.ttf` (таблицы WOFF, распакованные и
скопированные в файл TrueType, который Android умеет загружать; WOFF — лишь
сжатая обёртка) и `ui/icons/SetiIcons.kt`: глиф и цвет для каждого имени и
расширения файла, для тёмной и светлой тем, выбранные так же, как их выбирает
VS Code, — сначала имя, затем расширения начиная с самого длинного (`spec.ts`
раньше `ts`), затем язык, который VS Code назначил бы файлу, затем значение
по умолчанию. Иконка — это глиф, нарисованный цветом темы в 1,22 раза крупнее
рамки иконки, как это задаёт VS Code (150% от 13px в рамке 16px). Папки
остаются без иконки — как и у Seti в VS Code.

Seti UI распространяется под лицензией MIT, © 2014 Jesse Weed; уведомление
поставляется в APK как `assets/licenses/seti-ui.txt`. Прежних иконок (SVG из
Material Icon Theme, сконвертированных в векторы) больше нет.

### Прототип дизайна

`design/tokens.json` хранит чёрную тему: цвета, стили текста, радиусы, размеры
в dp и параметры анимации. Это единственное место, где живут эти числа:
прототип читает этот файл, а `tools/gen_theme.py` генерирует из него тему
Compose.

```
python tools/gen_theme.py
python tools/gen_design.py --out %USERPROFILE%\Desktop\ApkCLI-design.html
python tools/check_design.py
pwsh -File tools/shoot_design.ps1 [-Width 360 -Height 800]
python tools/check_strings.py [--unused]
```

`gen_theme.py` записывает `ui/theme/DesignTokens.kt` (цвета, стили текста,
радиусы, размеры, анимации; вручную никогда не правится) и копирует Inter 4.1
и JetBrains Mono 2.304 в `res/font`, а их уведомления OFL — в `assets/licenses`.
Это те же файлы, что встраивает прототип, из тех же закреплённых zip-архивов,
поэтому значение, изменённое в `tokens.json`, меняет прототип и приложение
вместе, как только отработают оба генератора.

`gen_design.py` собирает `design/prototype/{index.html,app.css,app.js}` в один
самодостаточный файл в `build/design`: без сервера, без сети, открывается на
телефоне. Контуры иконок он берёт из `Codicons.kt`, таблицы глифов — из
`SetiIcons.kt`, шрифт — из `res/font/seti.ttf`, а логотип — из
`ic_launcher_foreground.xml`, поэтому прототип не может разойтись с
приложением; если экран запрашивает иконку, которой в приложении нет, сборка
падает. Inter 4.1 и JetBrains Mono 2.304 берутся из их релизов, закреплены по
SHA-256, кешируются в `build/design-cache` и встраиваются как data URI, потому
что Chrome не загружает шрифты по `file://`.

`gen_catalogue.py` выводит списки моделей для прототипа из
[models.dev](https://models.dev/api.json) — открытой базы моделей, на основе
которой OpenCode строит собственный каталог провайдеров. Её поля — те же, что
в конфиге провайдера OpenCode: id модели, отображаемое имя, `limit.context`,
`limit.output` и флаги `attachment` / `reasoning` / `tool_call`; они
отображаются на `ModelSpec` приложения. Вывод вставляется в `app.js` как
`CATALOGUE`: это образцы данных для экранов, поскольку приложение запрашивает
у каждого эндпоинта его собственный список. Модели отбираются по правилу —
N самых новых из тех, что вызывают инструменты и возвращают текст, — поэтому
ни одна версия модели нигде не записана, а `--refresh` сам обновляет списки.
Форма пользовательского подключения повторяет структуру
`provider.<id>.options` из OpenCode: `baseURL`, `apiKey`, `headers`; известные
сервисы спрашивают только ключ.

Схемы, которые используют эти подключения, — базовые URL, куда передаются
учётные данные, как задаётся рассуждение, что `/models` сообщает, а что нет,
и что нужно Kiro, — описаны в [PROVIDERS.md](PROVIDERS.md), с источником для
каждого утверждения.

`check_design.py` загружает результат в headless Chrome, проходит по всем
маршрутам, нажимает каждый элемент управления (их 231) и сообщает о любой
ошибке JavaScript, чтобы опечатка в действии не осталась незамеченной.

`shoot_design.ps1` снимает скриншот каждого маршрута — их шестнадцать, от
`01-home` до `16-checkpoint`. Оба скрипта передают `?shot`, а он закрепляет
экран на размере из дизайн-токенов: headless Chrome здесь верстает под
504 CSS px, что бы ни было указано в `--window-size`, а снимает и вовсе другую
ширину, так что без закрепления снимок никогда не бывает заявленного размера.

`check_strings.py` сравнивает строки, на которые ссылается код, с теми, что
определены в `values/` (английские) и `values-ru/` (русские): каких нет в
одном из наборов, у каких плейсхолдеры `%1$s` различаются между ними, а с
`--unused` — на какие больше ничего не ссылается.

Физическое устройство всё это **не** охватывает: поведение во время выполнения
проверено только на эмуляторах x86_64 с API 34 и API 29. См. README.

Если сборка падает на разрешении зависимостей, начинать стоит с этого файла.

## AGP 9 использует встроенный Kotlin

Это единственная часть настройки, которая не сводится к номеру версии, и
первое, на чём споткнётся любой, кто заново генерирует файлы сборки.

AGP 9 сам предоставляет Kotlin. Применение `org.jetbrains.kotlin.android` —
жёсткая ошибка:

```
The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin
support since AGP 9.0
```

Две лазейки выглядят заманчиво, но обе ведут в тупик:

* `android.builtInKotlin=false` — всё равно падает, потому что
  `android.newDsl=true` — *тоже* значение по умолчанию в AGP 9, а прежнему
  плагину Kotlin нужен старый `BaseExtension`, и в итоге получается
  `ApplicationExtensionImpl$AgpDecorated_Decorated cannot be cast to ... BaseExtension`.
* `android.newDsl=false` — собственная документация AGP описывает это как
  временный обход.

Итак: плагина `kotlin.android` нет нигде, а версия *компилятора* Kotlin
поднята с закреплённой в AGP 2.2.10 до 2.3.20 из каталога версий — записью в
classpath блока buildscript корневого файла сборки. Компилятор Compose
поставляется вместе с Kotlin и должен в точности с ним совпадать, поэтому
оставить версию, закреплённую в AGP, — не вариант.

## Единственная закреплённая версия, которую нельзя просто так поменять

```
compose = "1.11.4"        -> требует compileSdk 36
composeMaterial3 = "1.4.0"
```

Compose 1.12 (а значит, и BOM `2026.08.00` и новее) **требует
`compileSdk = 37` и AGP 9.1.2+**. Если поднять `compose` выше 1.11.x, не подняв
заодно `compileSdk`, AGP выдаст ошибку о том, что зависимости нужен более новый
compile SDK.

BOM намеренно не используется. `compose-bom:2026.09.00` разрешается в 1.12.x,
а это притащило бы compileSdk 37 как побочный эффект того, что выглядит как
рутинное обновление версии. Явно закреплённые версии делают эту связь
видимой.

Чтобы перейти на Compose 1.12: выставьте `compileSdk = 37`, `agp = 9.1.2` или
новее, установите платформу android-37, затем поднимите `compose`.

## Инструменты сборки

| | Закреплено | Собрано с | Источник |
| --- | --- | --- | --- |
| AGP | 9.1.1 | 9.1.1 | Примечания к выпуску AGP 9.1.1, апрель 2026 года |
| Gradle | 9.8.0 (wrapper) | 9.8.0 | AGP 9.1.1 требует 9.3.1+; актуальной была 9.8.0 |
| JDK | 17 | Temurin 17.0.20.1 | Минимум для AGP 9.1.1 |
| Kotlin | 2.3.20 | 2.3.20 | kotlinlang.org, «What's new in Kotlin 2.3.20» |
| compileSdk | 36 | android-36 | AGP 9.1.1 поддерживает до 37 |
| Build tools | 36.0.0 | 36.0.0 | По умолчанию в AGP 9.1.1 |
| NDK | 28.2.13676358 | r28c | По умолчанию в AGP 9.1.1 |
| Rust | 1.85+ (`rust-version`) | 1.99.0 | текущая стабильная |
| cargo-ndk | — | 4.1.2 | `cargo install cargo-ndk` |

NDK намеренно закреплён на той версии, которую *сам* AGP использует по
умолчанию, чтобы strip-задачи AGP и кросс-компиляция через cargo-ndk работали
на одном тулчейне, а не подтягивали молча вторую копию размером ~1 ГБ.

На Windows без Visual Studio ставьте Rust с GNU-хостом — у него свой линкер,
поэтому скрипты `build.rs` (в первую очередь `libsqlite3-sys`, который собирает
SQLite из исходников на C) могут линковаться под хост:

```
rustup-init.exe -y --default-host x86_64-pc-windows-gnu --profile minimal
```

AGP 9.1.1 поддерживает API 37 и ниже, так что перейти на compileSdk 37 можно без
смены AGP — менять эту пару вместе заставляет только Compose.

### targetSdk

```
targetSdkPlay  = 36    Play требует 36+ для публикаций с 2026-08-31
targetSdkPower = 28    последний уровень, на котором разрешён exec из каталога данных
```

Android 16 не поднял минимальный `targetSdk` для установки на устройство: он
по-прежнему равен 24, как и в Android 15. Именно поэтому флейвор `power` всё
ещё можно установить. См. `ANDROID_EXEC_NOTES.md`.

## Библиотеки

Все они разрешились при первой же успешной сборке; колонки «уверенность»,
которая раньше здесь была, больше нет — это уже не догадки.

| | Закреплено |
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
| junit | 4.13.2, только JVM-тесты `:core` |

`material-icons-core` больше нет: каждая иконка — это codicon из `Codicons.kt`
(см. «Иконки» выше), как их рисует прототип. Набор Material и так был
заморожен на 1.7.8.

JUnit 4.13.2 — финальный выпуск JUnit 4, и именно его раннер юнит-тестов AGP
принимает без дополнительных движков. `./gradlew :core:testDebugUnitTest`
запускает JVM-тесты (формат обмена с Kiro и его учётные данные, фреймы
AWS event-stream, схемы подключений, списки моделей Anthropic); у хранилища
на Rust свои тесты — под `cargo test`.

### Nullability в OkHttp 5

В OkHttp 5 `Response.body` **не может быть null** (в OkHttp 4 он был
`ResponseBody?`). Код, написанный под API 4.x, компилируется с
предупреждениями и тащит за собой мёртвые ветки запасного варианта:
`response.body?.string() ?: ""` никогда не дойдёт до правой части
элвис-оператора. Адаптеры и `WebFetcher` исправлены во время первой сборки.

### Что намеренно не используется

| Не используется | Вместо этого | Почему |
| --- | --- | --- |
| Room | SQLite в ядре на Rust | Нет KSP, а значит, нет и версии процессора, которую приходится подгонять под каждое обновление Kotlin, — да и хранилище всё равно должно быть доступно из нативного кода |
| Hilt / Koin | `AgentRuntime` + `ServiceLocator` | Граф — это один объект с десятком полей, которые создаются сразу |
| Navigation | `Navigator` в `ui/nav` | Четыре пункта назначения; вкладки рабочей области — не пункты назначения |
| jsoup | `HtmlExtractor` | ~600 КБ ради задачи, в которой нет ни одного DOM-запроса |
| security-crypto | Keystore напрямую в `SecretStore` | Устаревшая (deprecated); API под ней стабилен, а это всего один файл |
| EventSource | `Sse` | Переподключение посреди генерации ответа прямо вредит |
| Material icons | Codicons | Собственные глифы VS Code, как их рисует прототип |
| apt / dpkg, Commons Compress | `core/pkg` (собственный ридер ar/tar, xz for Java) | В apt и dpkg вкомпилированы пути Termux; для `.deb` нужна лишь малая толика ar и tar |

## Rust

| | Закреплено | Примечания |
| --- | --- | --- |
| Rust | 1.85+ (`rust-version`) | собрано с 1.99.0 |
| jni | 0.21 | |
| rusqlite | 0.32, `bundled` | разрешился в 0.32.1 / libsqlite3-sys 0.30.1. `bundled` компилирует SQLite из исходников на C через clang из NDK, поэтому именно эта зависимость делает NDK обязательным, а не опциональным |
| grep-searcher / grep-regex / grep-matcher | 0.1 | Стабильный API |
| ignore / globset | 0.4 | |
| similar | 2 | |
| blake3, memchr, once_cell, parking_lot, regex | текущие мажорные версии | |
| android_logger | 0.14 | Только для таргета Android |

Две настройки в `native/Cargo.toml` — несущие:

```toml
panic = "unwind"   # слой JNI перехватывает паники; "abort" убил бы приложение
```

и в `native/.cargo/config.toml`:

```
-C link-arg=-Wl,-z,max-page-size=16384
-C link-arg=-Wl,-z,common-page-size=16384
```

Выравнивание страниц 16 КБ обязательно для Play с 1 февраля 2027 года. Драйвер
clang из NDK r28 делает это для C/C++, но Rust вызывает линкер сам. Проверено
на собранных библиотеках — каждый сегмент `LOAD` показывает выравнивание
`0x4000`:

```sh
llvm-readelf -l core/src/main/jniLibs/arm64-v8a/libapkcli_core.so
```

### Две привязки только для Android, которых нет в крейте `libc`

Обе находятся в `execshim`, и при первой кросс-компиляции обе не собрались:

* **`RTLD_NEXT`** для Android не определён, а его значение в bionic зависит от
  разрядности указателя: `(void*)-1` на LP64, `0xfffffffe` на ILP32. Они не
  взаимозаменяемы — на 32-битных платформах `-1` *и есть* `RTLD_DEFAULT`,
  поэтому с 64-битной константой `dlsym` искал бы `execve` в глобальной
  области видимости, находил бы сам перехватчик и уходил в рекурсию, пока не
  кончится стек. Именно поэтому `rtld_next()` ветвится по
  `target_pointer_width`.
* **`environ`** экспортируется из bionic и объявлен в его `<unistd.h>`, но
  `libc::environ` есть только для glibc-подобных таргетов, поэтому он объявлен
  в блоке `extern "C"`.

## Как проверить, что установлено

```sh
./gradlew envReport
rustup target list --installed
```

`envReport` печатает JDK, `ANDROID_HOME`, путь к NDK и где нашлись `cargo` и
`cargo-ndk`, так что упавший нативный шаг можно диагностировать, не читая
вывод Gradle.

## Если зависимости не разрешаются

1. Найдите действительно последнюю версию в
   [индексе Maven от Google](https://maven.google.com/web/index.html) или в
   [Maven Central](https://central.sonatype.com).
2. Поднимите её в `gradle/libs.versions.toml`.
3. Для всего из семейства Compose перед обновлением перечитайте первый раздел
   этого файла — именно связка `compose` ↔ `compileSdk` способна съесть
   полдня.

## Источники

* [Android Gradle plugin 9.1.1 release notes](https://developer.android.com/build/releases/agp-9-1-0-release-notes) — AGP, Gradle, JDK, NDK, SDK build-tools
* [Jetpack Compose August '26 release](https://developer.android.com/blog/posts/what-s-new-in-the-jetpack-compose-august-26-release) — требование Compose 1.12 / compileSdk 37 / AGP 9.1.2
* [BOM to library version mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping) — версии ядра Compose
* [Compose Material 3 releases](https://developer.android.com/jetpack/androidx/releases/compose-material3) — стабильный выпуск material3 1.4.0
* [Play target API level requirements](https://developer.android.com/google/play/requirements/target-sdk) — крайний срок 31 августа 2026 года для targetSdk 36
* [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes) — требование к выравниванию и крайний срок 1 февраля 2027 года
* [Android minimum targetSdk matrix](https://bayton.org/android/android-minimum-targetsdk-matrix/) — Android 16 оставляет минимум для установки на уровне 24
* [What's new in Kotlin 2.3.20](https://kotlinlang.org/docs/whatsnew2320.html)
* [sora-editor on Maven Central](https://central.sonatype.com/artifact/io.github.Rosemoe.sora-editor/editor)
* [OpenRouter `/api/v1/models`](https://openrouter.ai/api/v1/models) — запрашивался вживую ради полей метаданных, которые читает `ModelDiscovery`

Содержание этих источников прочитано и пересказано своими словами, а не
воспроизведено.
