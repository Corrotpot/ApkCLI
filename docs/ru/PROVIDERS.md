# Схемы подключений

[English](../PROVIDERS.md) · **Русский**

Что ApkCLI должен знать о каждом сервисе, чтобы пользователю оставалось только
вставить учётные данные. Проверено в октябре 2026 года; у каждого утверждения
есть источник. Сторонние источники помечены, потому что для Kiro первоисточника
нет.

Собственная модель эндпоинта в приложении — `core/.../model/Settings.kt`:
`Endpoint(baseUrl, protocol, authScheme, authHeaderName, extraHeaders, models)`,
`Protocol`, `AuthScheme`, `ModelSpec`. Факты по каждому сервису лежат в
`core/.../llm/ConnectionSchemes.kt` и заново применяются к эндпоинту при каждом
чтении настроек, так что исправленный заголовок доходит до подключений,
сохранённых более ранней версией, без чьей-либо правки. Описание каждой схемы
ниже заканчивается значениями, которые принимают эти поля.

Списка моделей здесь нет. После сохранения учётных данных приложение
запрашивает у каждого эндпоинта его собственный (`GET /models` или
`ListAvailableModels` для Kiro); `tools/gen_catalogue.py` лишь заполняет экраны
прототипа дизайна.

## Сводка

| Схема | Базовый URL | Учётные данные и куда они идут | Формат | Список моделей |
|---|---|---|---|---|
| Anthropic | `https://api.anthropic.com/v1` | `sk-ant-…` в `x-api-key` | Messages | **с лимитами** |
| OpenAI | `https://api.openai.com/v1` | `sk-…` в `Authorization: Bearer` | Chat Completions | только id |
| OpenRouter | `https://openrouter.ai/api/v1` | `sk-or-v1-…` в `Authorization: Bearer` | Chat Completions | **с лимитами** |
| Kiro | `https://codewhisperer.us-east-1.amazonaws.com`, затем `q.us-east-1.amazonaws.com` | токен аккаунта `aorAAAAAG…` (для Builder ID — вместе с его OIDC-клиентом), который обменивается на access-токен | Amazon Q, AWS event-stream | **с лимитами** (`ListAvailableModels`) |
| Кастомный | что введёт пользователь | необязательно | любой из двух | обычно только id |

Anthropic, OpenRouter и Kiro отдают в списке окно контекста и лимит ответа
каждой модели, так что для выбора модели достаточно сохранённого ключа. OpenAI
отдаёт только id; там модель начинает с осторожного окна, которое исправляет
первая ошибка переполнения (`Http.extractContextLimit`), а шторка «Модель»
показывает, какого из двух видов список (`ModelSource`). OpenRouter остаётся
вариантом по умолчанию при первом запуске.

## Проверено запросами

`tools/probe_models.py` не отправляет учётных данных и спрашивает у каждого
маршрута списка моделей, что он делает. Запуск 2026-10-08:

| Маршрут | Результат |
|---|---|
| `GET openrouter.ai/api/v1/models` | 200, 467 моделей, авторизация не нужна |
| `GET api.openai.com/v1/models` | 401 `invalid_request_error`, «Missing bearer authentication in header» |
| `GET api.anthropic.com/v1/models` | 401 `authentication_error`, «x-api-key header is required» |
| `GET codewhisperer.us-east-1.amazonaws.com/{,v1/models}` | 404 `UnknownOperationException` |

401 — хороший ответ: маршрут на месте, и не хватает только ключа. Anthropic
возвращает его независимо от того, отправлен ли `anthropic-version`, то есть
авторизация проверяется первой.

404 от Kiro — не ошибка в пути. Это AWS JSON-сервис, где операция задаётся
заголовком `X-Amz-Target`, а не URL, так что искать здесь путь бессмысленно.
Список моделей существует — это `ListAvailableModels` на
`q.<region>.amazonaws.com`, — но отвечает он только на access-токен аккаунта,
которого у этой пробы нет (см. раздел «Kiro» ниже).

Покрытие полей по 467 записям OpenRouter — именно оно решает, какую часть
`ModelSpec` можно заполнить автоматически:

| Поле | Есть |
|---|---|
| `context_length` | 100 % |
| `top_provider.context_length` | 98 % |
| `top_provider.max_completion_tokens` | 98 % |
| `architecture.input_modalities` | 100 % |
| `supported_parameters` | 99 % |
| `supported_parameters` содержит `tools` | 85 % |
| объект `reasoning` | 71 % |
| `reasoning.supported_efforts` | 41 % |

Так что даже здесь уровни рассуждения известны меньше чем для половины
каталога. Для остальных приложению приходится откатываться к `default_effort`
или не отправлять ничего, а не считать, что принимаются все семь уровней.

## Anthropic

- `POST /v1/messages` принимает `model`, `max_tokens` и `messages`; `system` —
  отдельное поле, а не сообщение.
  [Messages API](https://platform.claude.com/docs/en/api/messages)
- Каждый запрос обязан нести `anthropic-version`, например `2023-06-01`. SDK
  добавляют его сами; клиент, который о нём забыл, видит ошибки, похожие на
  неверный id модели.
  [Versioning](https://platform.claude.com/docs/en/api/versioning)
- Учётные данные идут в `x-api-key`.
  [Messages API](https://platform.claude.com/docs/en/api/messages)
- `GET /v1/models` перечисляет модели, доступные ключу, начиная с новых, с
  постраничностью через `limit` (до 1000), `after_id` и `has_more`. В каждой
  записи есть `max_input_tokens` и `max_tokens`, а также объект
  `capabilities`, который говорит, читает ли модель изображения
  (`image_input`), какие виды мышления она принимает
  (`thinking.types.{adaptive, enabled, disabled}`, у каждого — `supported`) и
  какие уровни усилия (от `effort.low` до `effort.max`). Окно, лимит ответа и
  управление рассуждением — всё приходит с сервера.
  [List models](https://docs.anthropic.com/en/api/models-list),
  [Get a model](https://docs.anthropic.com/en/api/models)
- Форма мышления менялась от поколения к поколению. До Claude 4.5 это
  `thinking: {type: "enabled", budget_tokens}` с бюджетом меньше `max_tokens`.
  Claude 4.6 добавляет `{type: "adaptive"}`, где модель сама решает, сколько
  думать, и объявляет бюджет устаревшим. Начиная с 4.7 адаптивное мышление —
  единственный вид: на `enabled` приходит 400, а некоторые из этих моделей
  отвергают и `disabled`. Новейшие модели не включают текст размышлений в
  ответ, если не отправить `display: "summarized"`.
  [Extended thinking](https://docs.anthropic.com/en/docs/build-with-claude/extended-thinking),
  [Adaptive thinking](https://docs.anthropic.com/en/docs/build-with-claude/adaptive-thinking),
  [Thinking](https://docs.anthropic.com/en/docs/build-with-claude/thinking)
- Регулятор усилия — `output_config.effort`, с уровнями от `low` до `max`;
  список моделей говорит, какие из них принимает модель.
  [Effort](https://docs.anthropic.com/en/docs/build-with-claude/effort)
- Мышление не сочетается с изменённой `temperature` или `top_k`, а `top_p`
  допускает только от 0,95 до 1. (Extended thinking, выше)
- Блоки мышления возвращаются подписанными, и на следующем ходе цикла с
  вызовами инструментов их нужно вернуть без изменений. Только поэтому в
  приложении и существует отдельный `Protocol.ANTHROPIC_MESSAGES`.
- Отсутствующий или неверный ключ даёт 401, в том числе на `/v1/models` (см.
  пробу), так что запрос списка моделей заодно служит проверкой ключа.
- Точный префикс ключа (`sk-ant-api03-`) в документации не перечислен, поэтому
  приложение при несовпадении предупреждает, а не отказывает; повторное
  нажатие «Сохранить» принимает ключ.

Как адаптер это читает (`AnthropicAdapter.messagesSpec`): модель, у которой в
`thinking.types` есть `adaptive`, получает `ReasoningSupport.ADAPTIVE`, и ей
отправляется `thinking: {type: "adaptive", display: "summarized"}`. Вариант
«выключено» предлагается только там, где указан `disabled`; модель без него
помечается как думающая всегда. Модель, у которой указан `enabled`, или модель
от шлюза, чей список вообще не сообщает возможностей, получает `BUDGET`.
`output_config.effort` отправляется только с уровнем, который модель
публикует, а пока мышление включено, параметры сэмплирования не отправляются.

Эндпоинт: `baseUrl = https://api.anthropic.com/v1`,
`protocol = ANTHROPIC_MESSAGES`, `authScheme = X_API_KEY`, без дополнительных
заголовков (заголовок версии добавляет `Http.kt`).

## OpenAI

- `POST /v1/chat/completions`, учётные данные — как bearer-токен.
  Необязательные заголовки `OpenAI-Organization` / `OpenAI-Project` выбирают
  организацию или проект для ключей, которые относятся к нескольким.
  [API overview](https://developers.openai.com/api/reference/overview/)
- `GET /v1/models` возвращает id, владельца и доступность — ни окна контекста,
  ни лимита ответа.
  [List models](https://developers.openai.com/api/reference/resources/models/methods/list)
- Модели с рассуждением принимают уровень усилия: `reasoning: {effort}` в
  Responses API и `reasoning_effort` в Chat Completions.
  [Reasoning guide](https://developers.openai.com/api/docs/guides/reasoning/)
  В 2026 году уровни такие: `none`, `minimal`, `low`, `medium`, `high`,
  `xhigh`, `max`, а какие из них принимает модель, зависит от её поколения.
  (Сторонние источники: [effort levels per family](https://github.com/docker/docker-agent/blob/main/docs/guides/thinking/index.md),
  [five levels on GPT-5.4](https://docs.bswen.com/blog/2026-04-04-gpt54-reasoning-effort-levels-guide/))
- Модели с рассуждением отвергают `max_tokens` в пользу `max_completion_tokens`
  и отвергают параметры сэмплирования вроде `temperature`.
  [Response length](https://help.openai.com/en/articles/5072518-controlling-the-length-of-openai-model-responses)
  Запрос с обоими вариантами написания тоже отвергается, поэтому OpenAI
  получает только `max_completion_tokens`. Все остальные серверы формата OpenAI
  получают оба, потому что старые шлюзы знают только `max_tokens`.
- Семейства `pro` и `codex` обслуживаются только через `POST /v1/responses`, а
  это третий адаптер со своими элементами рассуждения и состоянием на стороне
  сервера. Приложение его не реализует (см. заметку в `Settings.kt`), поэтому
  такие модели показываются в списке, но с пометкой.
  [Responses API](https://developers.openai.com/api/docs)
- В ходу префиксы ключей `sk-proj-`, `sk-svcacct-` и устаревший `sk-`;
  документация их не перечисляет, так что и здесь приложение только
  предупреждает.

Эндпоинт: `baseUrl = https://api.openai.com/v1`, `protocol = OPENAI_CHAT`,
`authScheme = BEARER`; отправляется только `max_completion_tokens`.

## OpenRouter

- `POST /chat/completions` с bearer-токеном.
  [Authentication](https://openrouter.ai/docs/api_reference/authentication.md)
- Заголовки атрибуции необязательны: `HTTP-Referer` указывает сайт приложения,
  а `X-OpenRouter-Title` — актуальный заголовок с отображаемым именем, при этом
  `X-Title` по-прежнему принимается. `X-OpenRouter-Categories` принимает
  известные слаги, например `cli-agent`.
  [App attribution](https://openrouter.ai/docs/app-attribution.md)
  Приложение отправляет оба заголовка с названием и не отправляет
  `HTTP-Referer`: там должен быть сайт, которым оно владеет.
- Ключи — это `sk-or-v1-` и 64 шестнадцатеричных символа; это стоит проверить,
  прежде чем тратить запрос.
  [Secret formats](https://openrouter.ai/docs/guides/features/guardrails/secret-formats.md)
  Пользователи создают их на [openrouter.ai/keys](https://openrouter.ai/keys).
- `GET /api/v1/key` проверяет ключ, не генерируя токенов, и сообщает `label`,
  `limit`, `limit_remaining`, `usage`, `is_free_tier` и
  `free_model_daily_requests`. На неверный ключ приходит 401.
  [Get current API key](https://openrouter.ai/docs/api/api-reference/api-keys/get-current-api-key.md)
  Это и есть проверка ключа в приложении (`ConnectionScheme.checkPath`): списку
  моделей ключ не нужен, поэтому он не может сказать, годится ли ключ.
  `GET /api/v1/credits` теперь требует ключ управления (management key), так что
  приложение не должно его вызывать.
  [Credits](https://openrouter.ai/docs/api/api-reference/credits/get-remaining-credits.md)
- `GET /api/v1/models` не требует авторизации и заполняет большую часть
  `ModelSpec`: `context_length`,
  `top_provider.{context_length, max_completion_tokens}`,
  `architecture.input_modalities`, `supported_parameters` и объект `reasoning`
  с `supported_efforts` (по убыванию), `default_effort`, `default_enabled` и
  `mandatory`.
  [List models](https://openrouter.ai/docs/api/api-reference/models/list-all-models-and-their-properties.md)
  На практике важна таблица покрытия выше: окно есть всегда, лимит ответа —
  почти всегда, а уровни рассуждения — у 41 % записей. `reasoning` полностью
  отсутствует у моделей, которые не рассуждают, и у роутеров, а
  `supported_efforts`, равный null, означает, что принимается любой уровень, —
  так что отсутствие поля и null нужно различать.
- Объект запроса `reasoning` принимает либо `effort`, либо `max_tokens`, но не
  оба сразу, плюс `exclude` и `enabled`. Неподдерживаемый уровень заменяется
  ближайшим поддерживаемым. Рассуждение оплачивается из `max_tokens`, поэтому
  исчерпанный бюджет возвращает `finish_reason: "length"` с пустым
  содержимым.
  [Reasoning tokens](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens.md)
- Рассуждение возвращается как `reasoning` и `reasoning_details` (типы
  `reasoning.text`, `reasoning.summary`, `reasoning.encrypted`), и при вызовах
  инструментов эти записи нужно вернуть в сообщении ассистента в исходном
  порядке. (reasoning-tokens, выше)
- Данные об использовании (usage) включаются всегда; `usage.include` и
  `stream_options.include_usage` устарели и ничего не делают, а поток
  заканчивается фрагментом с usage перед `[DONE]`.
  [Usage accounting](https://openrouter.ai/docs/guides/administration/usage-accounting.md),
  [Streaming](https://openrouter.ai/docs/api_reference/streaming.md)
- Ошибки имеют вид `{error: {code, message, metadata}}`. После того как ответ
  начался, они приходят внутри потока, а не кодом статуса, поэтому клиенту
  нужно искать `error` верхнего уровня в каждом фрагменте. 402 несёт
  `metadata.limit_source`, по которому низкий баланс отличается от лимита
  ключа.
  [Errors](https://openrouter.ai/docs/api_reference/errors-and-debugging.md),
  [Limits](https://openrouter.ai/docs/api_reference/limits.md)
- `POST /api/v1/messages` обслуживает и формат Anthropic, но приложению он не
  нужен, поскольку нормализованные метаданные моделей живут на маршруте OpenAI.
  [Anthropic messages](https://openrouter.ai/docs/api/api-reference/anthropic-messages/create-a-message.md)

Эндпоинт: `baseUrl = https://openrouter.ai/api/v1`, `protocol = OPENAI_CHAT`,
`authScheme = BEARER`, `extraHeaders = {X-OpenRouter-Title, X-Title}`,
`checkPath = key`.

## Kiro

У Kiro нет публичного HTTP API. Схема ниже взята у
[Kiro Account Manager](https://github.com/chaogei/Kiro-account-manager) —
Electron-приложения, чей API-прокси общается с бэкендом так же, как Kiro IDE
(сверено с его веткой `main`, файлами `src/main/proxy/kiroApi.ts`,
`translator.ts` и `src/main/index.ts` в октябре 2026 года). Оно распространяется
под AGPL-3.0, поэтому `KiroCredential`, `KiroAuth`, `KiroAdapter`, `KiroWire` и
`KiroToolLeak` написаны по его фактам, а не по его коду. Там, где оно молчит,
факты взяты из [kiro-gateway](https://github.com/jwadow/kiro-gateway) (AGPL,
только факты) и [9router](https://github.com/decolua/9router) (MIT). Считайте
всё это результатом обратной разработки, а не документацией, и ожидайте, что
оно будет меняться.

- Бэкенд — Amazon Q Developer / CodeWhisperer. Собственное десктопное
  приложение Kiro и его CLI используют `q.<region>.amazonaws.com` и
  `codewhisperer.<region>.amazonaws.com` с маршрутом
  `generateAssistantResponse`. Старый путь `runtime.<region>.kiro.dev` устарел
  и теперь отвечает 400.
  [9router issue #3774](https://github.com/decolua/9router/issues/3774)
- Ответы приходят как AWS event-stream, а не SSE, поэтому здесь нужен
  собственный адаптер, а не доработка адаптера OpenAI.
  [kiroxy architecture](https://github.com/nopperabbo/kiroxy/blob/main/docs/ARCHITECTURE.md),
  [kiro-reverse-api](https://github.com/tanu360/kiro-reverse-api/blob/main/README.md)
- Единственное, что должен предоставить пользователь, — refresh-токен
  аккаунта, который начинается с `aorAAAAAG`. Менеджеры аккаунтов принимают
  ровно это и регион, по умолчанию `us-east-1`.
  [OmniRoute Kiro setup](https://github.com/UmeshOnAI/OmniRoute-AI-Gateway/blob/main/docs/guides/KIRO_SETUP.md)
  Kiro IDE хранит его в `~/.aws/sso/cache/kiro-auth-token.json`; на телефоне
  такого файла нет, поэтому токен вставляется вручную.
  [kiro-gateway](https://github.com/jwadow/kiro-gateway/blob/main/README.md)

Как это делает приложение, вслед за менеджером:

- **Учётные данные** (`KiroCredential`). Единственное поле принимает то же, что
  импорт менеджера: один refresh-токен (`aorAAAAAG…`, вход через Google или
  GitHub); JSON `{refreshToken, clientId, clientSecret, region, authMethod,
  provider, profileArn}` — сам по себе, в списке или вложенным в
  `credentials`, как менеджер экспортирует аккаунт (собственный
  `kiro-auth-token.json` из Kiro IDE при входе через Google или GitHub
  вставляется как есть); или его строчный формат
  `email----password----refreshToken----clientId----clientSecret[----provider]`.
  Хранится в компактном виде, одной строкой. Всё остальное считается API-ключом
  Kiro: он отправляется как bearer с `TokenType: API_KEY` и никогда не
  обменивается (по данным 9router; у менеджера API-ключей нет).
- **Обмен.** Токен входа через Google или GitHub отправляется на
  `POST https://prod.us-east-1.auth.desktop.kiro.dev/refreshToken` с
  `{"refreshToken": …}` и user agent Kiro IDE. Токен Builder ID или Identity
  Center отправляется на `POST https://oidc.<region>.amazonaws.com/token` с
  `{clientId, clientSecret, refreshToken, grantType: "refresh_token"}`; Kiro IDE
  хранит этот клиент в `~/.aws/sso/cache/<clientIdHash>.json`. В обоих случаях
  ответ содержит `accessToken`, `expiresIn` (3600 с, если поля нет; токен
  обновляется за 2 минуты до истечения) и, возможно, новый `refreshToken`,
  который заменяет сохранённый.
- **Профиль.** Его называет каждый запрос. Вход через Google или GitHub
  использует профиль, который вернул обмен, а иначе — профиль, общий для всех
  таких входов,
  `arn:aws:codewhisperer:us-east-1:699475941385:profile/EHGA3GRVQMUK`; Builder
  ID использует фиксированный профиль Kiro IDE,
  `arn:aws:codewhisperer:us-east-1:638616132270:profile/AAAACCCCXXXX`. Identity
  Center организации (`provider: Enterprise` или внешний поставщик
  удостоверений) ищется один раз через
  `POST https://codewhisperer.<us-east-1|eu-central-1>.amazonaws.com/ListAvailableProfiles`
  с телом `{}` и хранится вместе с учётными данными. У менеджера есть ещё
  фиксированный запасной вариант для организаций, у которых поиск не удался;
  он не задокументирован как профиль Kiro IDE, поэтому приложение его не
  использует.
- **Генерация.** Сначала
  `POST https://codewhisperer.us-east-1.amazonaws.com/generateAssistantResponse`,
  затем `https://q.us-east-1.amazonaws.com/generateAssistantResponse`, оба с
  маршрутизацией по пути (без `X-Amz-Target`) и в Северной Вирджинии, каким бы
  ни был регион аккаунта. Любой отказ, кроме 401 или 403, ведёт к следующему
  адресу; 401 или 403 означают проблему с токеном, поэтому refresh-токен
  обменивается ещё раз, и оба адреса пробуются снова.
- **Заголовки** — те, что менеджер отправляет от имени Kiro IDE:
  `Authorization: Bearer`,
  `User-Agent: aws-sdk-js/1.0.34 ua/2.1 os/linux#<kernel> lang/js md/nodejs#22.22.0 api/codewhispererstreaming#1.0.34 m/E KiroIDE-0.12.155-<machineId>`,
  `x-amz-user-agent: aws-sdk-js/1.0.34 KiroIDE 0.12.155 <machineId>`,
  `x-amzn-kiro-agent-mode: vibe`, свежий `amz-sdk-invocation-id`,
  `amz-sdk-request: attempt=1; max=3` и `TokenType: EXTERNAL_IDP` для внешнего
  поставщика удостоверений. Machine id — это SHA-256 от
  `kiro-device-<connection id>`: так менеджер выводит его для аккаунта без
  привязанного id. Одно дополнение: `x-amzn-codewhisperer-optout: true` в
  каждом вызове, тогда как менеджер отправляет его только в запросах списков;
  этот заголовок просит не использовать разговор для улучшения сервиса.
- **Модели.** `GET https://q.<us-east-1|eu-central-1>.amazonaws.com/ListAvailableModels?origin=AI_EDITOR&maxResults=50&profileArn=…`
  с постраничностью через `nextToken` (не больше 10 страниц) отвечает
  собственным списком аккаунта: `modelId`, `modelName`, `description`,
  `rateMultiplier`, `supportedInputTypes`,
  `tokenLimits.{maxInputTokens, maxOutputTokens}` и
  `additionalModelRequestFieldsSchema`. Что видит аккаунт, зависит от его
  тарифа. Модель, которая не сообщает окно, считается моделью на 200 000
  токенов.
- **Рассуждение.** Форму задаёт схема полей запроса: модель, в схеме которой
  есть `output_config.effort.enum`, получает `thinking: {type: "adaptive",
  display: "summarized"}` вместе с `output_config.effort`; модель с
  `reasoning.effort.enum` получает `reasoning.effort`. Уровни берутся из этого
  enum, а уровень, которого модель не перечисляет, становится её высшим. Без
  схемы Claude 4.6 и новее получают первую форму (4.6 останавливается на
  `max`, без `xhigh`), а семейство GPT-5.6 — вторую.
- **Тело запроса.** `conversationState` с `agentContinuationId` (новым для
  каждого запроса), `agentTaskType: vibe`, `chatTriggerType: MANUAL`,
  `conversationId`, который остаётся одним и тем же в рамках одного чата,
  `history` (опускается, если пуста) и `currentMessage.userInputMessage`; рядом
  с ним идут `profileArn`, `inferenceConfig.{maxTokens, temperature, topP}` и
  `additionalModelRequestFields`. Каждый пользовательский ход несёт `content`,
  `modelId` и `origin: AI_EDITOR`, плюс `images` и
  `userInputMessageContext.{tools, toolResults}`; инструменты передаются только
  в текущем сообщении. Системного промпта верхнего уровня нет: это первый ход
  истории с ответом «I will follow these instructions.» — так его отправляет
  Kiro IDE. Рассуждение обратно не отправляется, поскольку `reasoningContent` в
  истории отвергается как некорректный. Пустых ходов нет: ход только из вызовов
  инструментов содержит один пробел, ответ без содержимого — «I understand.»,
  пустой вопрос — «Continue». Результаты инструментов всегда идут со
  `status: success`, а о неудаче говорит их текст — так их отправляет менеджер.
  Приложение просит не больше 32 000 токенов ответа.
- **Поток.** Кроме текста (`assistantResponseEvent`, `codeEvent`), рассуждения
  и вызовов инструментов, он заканчивается тремя событиями — так это выглядело
  вживую (октябрь 2026 года): `metadataEvent` с причиной остановки и больше
  ничем, `contextUsageEvent` с заполненностью окна в процентах и
  `meteringEvent` со стоимостью запроса в кредитах. Количества токенов нет —
  ни кэшированных, ни прочих. Декодер всё равно читает `tokenUsage`,
  `usageEvent` и `metricsEvent` на случай, если они придут, но не превращает
  процент в число токенов промпта: это доля окна, которое список моделей
  сообщает не всегда, в неё входит ответ, а оценщик токенов калибруется по
  любому пришедшему счётчику. Кредиты и процент пишутся в лог для каждого
  запроса. `invalidStateEvent` показывается в ответе как предупреждение, а
  ссылки из `supplementaryWebLinksEvent` и `citationEvent` перечисляются в его
  конце. `error` внутри полезной нагрузки события делает ход неудачным.
- **Аргументы инструмента приходят поздно.** Имя вызова и его короткие
  аргументы (путь) приходят сразу; длинное строковое значение (содержимое
  файла) приходит, только когда модель закончила его писать, и целиком: 37
  секунд тишины, затем 7 КБ за 1,5 с — для одного `fs_write`. Поэтому карточка
  инструмента показывает и то, сколько модель уже пишет, и то, сколько успело
  прийти, а также путь из первых фрагментов.
- **Вызовы, записанные текстом.** Бэкенд иногда пропускает вызов инструмента в
  виде XML, которым тот был записан (`<function_calls><invoke name="…"><parameter
  name="…">…</parameter></invoke>`, причём открывающий тег иногда искажается
  до голого `count`), внутри текста и вразбивку по событиям. Менеджер ловит
  такие вызовы и выполняет их; так же поступает `KiroToolLeak` — только для
  инструментов, объявленных в запросе, и не тогда, когда тот же вызов пришёл
  ещё и в структурированном виде.
- **Ошибки.** `CONTENT_LENGTH_EXCEEDS_THRESHOLD` или 400 на тело больше
  600 000 байт считаются переполнением контекста, и контекст сжимается;
  `MONTHLY_REQUEST_COUNT` — исчерпанной месячной квотой; `INVALID_MODEL_ID` —
  моделью, которой у аккаунта нет.
- **Острый угол.** Kiro IDE держит тот же refresh-токен. Обмен здесь, который
  его ротирует, оставляет IDE с недействительным токеном, и IDE
  разлогинивается; обмен в IDE делает то же самое с приложением, и его
  следующий запрос просит актуальный токен. Форма предупреждает об этом до
  того, как токен вставлен.
- **Кэширование промпта.** Сервис сам кэширует повторяющийся префикс; клиент
  управляет лишь тем, повторяется ли его префикс. Замерено на claude-opus-5:
  один ход агента из пяти запросов (чтение файла на 55 КБ, три команды, ответ),
  стоимость каждого в кредитах из `meteringEvent`:

  | | 1 | 2 | 3 | 4 | 5 |
  |---|---|---|---|---|---|
  | стабильный системный ход, точки кэша | 0.139* | 0.245 | 0.245 | 0.147 | 0.135 |
  | стабильный системный ход, без точек | 0.227 | 0.245 | 0.245 | 0.147 | 0.135 |
  | меняющийся системный ход, точки кэша | 0.225 | 0.244 | 0.245 | 0.246 | 0.234 |

  \* системный префикс ещё оставался в кэше от предыдущего прогона.

  Содержимое становится дешёвым со второго запроса после того, как его впервые
  отправили. Когда строка о заполненности окна стоит внутри системного хода,
  каждый запрос — холодный, и к концу хода это примерно на 40% дороже; когда
  она в текущем сообщении (`SystemPrompt.Parts`, `<app-context>`), префикс
  повторяется. Точки кэша ничего измеримого не изменили. Они остаются —
  `cachePoint: {type: "default"}` на системном ходе и на последнем
  пользовательском ходе истории, так, как менеджер отправляет их для
  `cache_control` клиента, — для моделей, чья запись в списке говорит, что они
  кэшируют (`promptCaching: {supportsPromptCaching,
  maximumCacheCheckpointsPerRequest, minimumTokensPerCacheCheckpoint}`); ни один
  запрос с ними отвергнут не был (`KiroPromptCache`).
- **Что не перенято.** Пул аккаунтов менеджера, его собственные добавки к
  промпту и сами процедуры входа (вход через браузер, импорт SSO-cookie);
  приложение принимает уже существующий токен.
- Ничто из этого не одобрено AWS, и это собственная подписка пользователя,
  которой пользуются из другого клиента. Форма подключения об этом говорит.

Эндпоинт: `baseUrl = https://q.us-east-1.amazonaws.com` (хост списка моделей;
запросы идут на две поверхности, описанные выше), `protocol = KIRO`,
`authScheme = BEARER`. Схема авторизации говорит лишь о том, что нужны учётные
данные: адаптер их обменивает, а не `Http` отправляет как есть. Префикс ключа —
`aorAAAAAG`, или структурированный вход, как описано выше.

## Кастомный эндпоинт

`POST {base}/chat/completions` — то, на чём говорит любой локальный сервер:
vLLM, llama.cpp, Ollama, LM Studio и прокси. Маршрут Anthropic встречается
реже: он есть у Ollama начиная с 0.14 и у LM Studio, а у vLLM и llama.cpp, как
правило, нет.
[Ollama Anthropic compatibility](https://docs.ollama.com/api/anthropic-compatibility),
[LM Studio](https://lmstudio.ai/docs/developer/anthropic-compat/messages)

Это единственная схема, которая спрашивает URL и формат, потому что ни то ни
другое нельзя вывести. Локальному серверу обычно вообще не нужны учётные
данные, и `NetworkHosts` разрешает обычный HTTP к частному адресу, но запрещает
его через интернет. Схема авторизации следует из формы, а не спрашивается
отдельно: нет ключа — `AuthScheme.NONE`, ключ для сервера формата Anthropic
идёт в `x-api-key` (`X_API_KEY`), любой другой ключ — bearer-токен.
Дополнительные заголовки здесь по-прежнему доступны — для прокси, которым они
нужны.

Содержимое сторонних страниц выше пересказано.
