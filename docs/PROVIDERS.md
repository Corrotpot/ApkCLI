# Connection schemes

**English** · [Русский](ru/PROVIDERS.md)

What ApkCLI has to know about each service so that pasting a credential is the
only thing left to do. Checked in October 2026; every claim carries its source.
Third-party sources are marked, because for Kiro there is no first-party one.

The app's own model of an endpoint is `core/.../model/Settings.kt`:
`Endpoint(baseUrl, protocol, authScheme, authHeaderName, extraHeaders, models)`,
`Protocol`, `AuthScheme`, `ModelSpec`. The facts per service live in
`core/.../llm/ConnectionSchemes.kt` and are re-applied to an endpoint every
time settings are read, so a corrected header reaches connections saved by an
earlier version without anyone editing them. Each scheme below ends with the
values those fields take.

Nothing here is a model list. The app asks every endpoint for its own after a
credential is saved (`GET /models`, or `ListAvailableModels` for Kiro);
`tools/gen_catalogue.py` only fills the design prototype's screens.

## Summary

| Scheme | Base URL | Credential, where it goes | Format | Model list |
|---|---|---|---|---|
| Anthropic | `https://api.anthropic.com/v1` | `sk-ant-…` in `x-api-key` | Messages | **with limits** |
| OpenAI | `https://api.openai.com/v1` | `sk-…` in `Authorization: Bearer` | Chat Completions | ids only |
| OpenRouter | `https://openrouter.ai/api/v1` | `sk-or-v1-…` in `Authorization: Bearer` | Chat Completions | **with limits** |
| Kiro | `https://codewhisperer.us-east-1.amazonaws.com`, then `q.us-east-1.amazonaws.com` | `aorAAAAAG…` account token (Builder ID: with its OIDC client), exchanged for an access token | Amazon Q, AWS event-stream | **with limits** (`ListAvailableModels`) |
| Custom | whatever the user types | optional | either | ids, usually |

Anthropic, OpenRouter and Kiro answer the listing with each model's context
window and output cap, so a saved key is all the model picker needs. OpenAI
answers with ids only; there a model starts from a cautious window, which the
first overflow error corrects (`Http.extractContextLimit`), and the model sheet
says which of the two kinds a list is (`ModelSource`). OpenRouter stays the
first-run default.

## Checked by request

`tools/probe_models.py` sends no credentials and asks each listing route what
it does. Run on 2026-10-08:

| Route | Result |
|---|---|
| `GET openrouter.ai/api/v1/models` | 200, 467 models, no auth needed |
| `GET api.openai.com/v1/models` | 401 `invalid_request_error`, "Missing bearer authentication in header" |
| `GET api.anthropic.com/v1/models` | 401 `authentication_error`, "x-api-key header is required" |
| `GET codewhisperer.us-east-1.amazonaws.com/{,v1/models}` | 404 `UnknownOperationException` |

A 401 is the good answer: the route is there and a key is the only thing
missing. Anthropic returns it whether or not `anthropic-version` is sent, so
auth is checked first.

The 404 from Kiro is not a wrong path. That surface is an AWS JSON service
where the operation comes from the `X-Amz-Target` header, not the URL, so there
is no path to find. The model list does exist, as `ListAvailableModels` on
`q.<region>.amazonaws.com`, but it answers only to an account's access token,
which this probe does not have (see Kiro below).

Field coverage across OpenRouter's 467 entries, which is what decides how much
of `ModelSpec` can be filled in automatically:

| Field | Present |
|---|---|
| `context_length` | 100 % |
| `top_provider.context_length` | 98 % |
| `top_provider.max_completion_tokens` | 98 % |
| `architecture.input_modalities` | 100 % |
| `supported_parameters` | 99 % |
| `supported_parameters` contains `tools` | 85 % |
| `reasoning` object | 71 % |
| `reasoning.supported_efforts` | 41 % |

So even here the effort levels are known for under half the catalogue. For the
rest the app has to fall back to `default_effort` or to sending nothing, rather
than assuming the seven levels are all accepted.

## Anthropic

- `POST /v1/messages` takes `model`, `max_tokens` and `messages`; `system` is a
  separate field rather than a message.
  [Messages API](https://platform.claude.com/docs/en/api/messages)
- Every request must carry `anthropic-version`, for example `2023-06-01`. The
  SDKs add it; a client that forgets it sees errors that look like a wrong
  model id.
  [Versioning](https://platform.claude.com/docs/en/api/versioning)
- The credential goes in `x-api-key`.
  [Messages API](https://platform.claude.com/docs/en/api/messages)
- `GET /v1/models` lists the models the key can use, newest first, paged with
  `limit` (up to 1000), `after_id` and `has_more`. Each entry carries
  `max_input_tokens` and `max_tokens`, and a `capabilities` object that says
  whether the model reads images (`image_input`), which kinds of thinking it
  takes (`thinking.types.{adaptive, enabled, disabled}`, each with
  `supported`) and which effort levels (`effort.low` to `effort.max`). The
  window, the output cap and the reasoning controls all come from the server.
  [List models](https://docs.anthropic.com/en/api/models-list),
  [Get a model](https://docs.anthropic.com/en/api/models)
- Thinking changed shape across generations. Up to Claude 4.5 it is
  `thinking: {type: "enabled", budget_tokens}`, with the budget below
  `max_tokens`. Claude 4.6 adds `{type: "adaptive"}`, where the model decides
  how much to think, and deprecates the budget. From 4.7 on adaptive is the
  only kind: `enabled` answers 400, and several of these models refuse
  `disabled` as well. The newest models leave the thinking text out of the
  response unless `display: "summarized"` is sent.
  [Extended thinking](https://docs.anthropic.com/en/docs/build-with-claude/extended-thinking),
  [Adaptive thinking](https://docs.anthropic.com/en/docs/build-with-claude/adaptive-thinking),
  [Thinking](https://docs.anthropic.com/en/docs/build-with-claude/thinking)
- The effort dial is `output_config.effort`, with levels from `low` to `max`;
  the listing says which ones a model takes.
  [Effort](https://docs.anthropic.com/en/docs/build-with-claude/effort)
- Thinking does not combine with a changed `temperature` or `top_k`, and
  allows `top_p` only between 0.95 and 1. (Extended thinking, above)
- Thinking blocks come back signed and have to be returned unchanged on the
  next turn of a tool-use loop. This is the whole reason
  `Protocol.ANTHROPIC_MESSAGES` exists separately in the app.
- A missing or bad key answers 401, `/v1/models` included (see the probe), so
  listing the models doubles as the key check.
- The exact key prefix (`sk-ant-api03-`) is not enumerated in the docs, so the
  app warns on a mismatch instead of refusing; pressing save again accepts it.

How the adapter reads that (`AnthropicAdapter.messagesSpec`): a model whose
`thinking.types` lists `adaptive` gets `ReasoningSupport.ADAPTIVE` and is sent
`thinking: {type: "adaptive", display: "summarized"}`. "Off" is offered only
where `disabled` is listed; a model without it is marked as always thinking. A
model that lists `enabled`, or one from a gateway whose listing reports no
capabilities at all, gets `BUDGET`. `output_config.effort` goes out only with a
level the model publishes, and no sampling parameters go out while thinking is
on.

Endpoint: `baseUrl = https://api.anthropic.com/v1`,
`protocol = ANTHROPIC_MESSAGES`, `authScheme = X_API_KEY`, no extra headers
(the version header is added by `Http.kt`).

## OpenAI

- `POST /v1/chat/completions`, credential as a bearer token. Optional
  `OpenAI-Organization` / `OpenAI-Project` headers select an org or project for
  keys that belong to several.
  [API overview](https://developers.openai.com/api/reference/overview/)
- `GET /v1/models` returns id, owner and availability — no context window and
  no output cap.
  [List models](https://developers.openai.com/api/reference/resources/models/methods/list)
- Reasoning models take an effort, `reasoning: {effort}` in the Responses API
  and `reasoning_effort` in Chat Completions.
  [Reasoning guide](https://developers.openai.com/api/docs/guides/reasoning/)
  In 2026 the levels run `none`, `minimal`, `low`, `medium`, `high`, `xhigh`,
  `max`, and which of them a model accepts depends on its generation.
  (Third-party: [effort levels per family](https://github.com/docker/docker-agent/blob/main/docs/guides/thinking/index.md),
  [five levels on GPT-5.4](https://docs.bswen.com/blog/2026-04-04-gpt54-reasoning-effort-levels-guide/))
- Reasoning models reject `max_tokens` in favour of `max_completion_tokens`,
  and reject sampling parameters such as `temperature`.
  [Response length](https://help.openai.com/en/articles/5072518-controlling-the-length-of-openai-model-responses)
  A request that carries both spellings is refused too, so OpenAI gets only
  `max_completion_tokens`. Every other OpenAI-format server gets both, because
  older gateways know only `max_tokens`.
- The `pro` and `codex` families are served only through `POST /v1/responses`,
  which is a third adapter with its own reasoning items and server-side state.
  The app does not implement it (see the note in `Settings.kt`), so such models
  are listed but marked.
  [Responses API](https://developers.openai.com/api/docs)
- Key prefixes in circulation are `sk-proj-`, `sk-svcacct-` and the legacy
  `sk-`; the docs do not enumerate them, so again the app only warns.

Endpoint: `baseUrl = https://api.openai.com/v1`, `protocol = OPENAI_CHAT`,
`authScheme = BEARER`; only `max_completion_tokens` is sent.

## OpenRouter

- `POST /chat/completions` with a bearer token.
  [Authentication](https://openrouter.ai/docs/api_reference/authentication.md)
- Attribution headers are optional: `HTTP-Referer` identifies the app's site
  and `X-OpenRouter-Title` is the current display-name header, with `X-Title`
  still accepted. `X-OpenRouter-Categories` takes known slugs such as
  `cli-agent`.
  [App attribution](https://openrouter.ai/docs/app-attribution.md)
  The app sends both title headers and no `HTTP-Referer`, which would have to
  be a site it owns.
- Keys are `sk-or-v1-` plus 64 hex characters, which is worth checking before
  spending a request.
  [Secret formats](https://openrouter.ai/docs/guides/features/guardrails/secret-formats.md)
  Users create them at [openrouter.ai/keys](https://openrouter.ai/keys).
- `GET /api/v1/key` validates a key without generating tokens and reports
  `label`, `limit`, `limit_remaining`, `usage`, `is_free_tier` and
  `free_model_daily_requests`. An invalid key returns 401.
  [Get current API key](https://openrouter.ai/docs/api/api-reference/api-keys/get-current-api-key.md)
  This is the app's key check (`ConnectionScheme.checkPath`): the model
  listing needs no key, so it cannot say whether one is good.
  `GET /api/v1/credits` now needs a management key, so the app must not call it.
  [Credits](https://openrouter.ai/docs/api/api-reference/credits/get-remaining-credits.md)
- `GET /api/v1/models` needs no auth and fills most of `ModelSpec`:
  `context_length`, `top_provider.{context_length, max_completion_tokens}`,
  `architecture.input_modalities`, `supported_parameters`, and a `reasoning`
  object with `supported_efforts` (highest first), `default_effort`,
  `default_enabled` and `mandatory`.
  [List models](https://openrouter.ai/docs/api/api-reference/models/list-all-models-and-their-properties.md)
  The coverage table above is the part that matters in practice: the window is
  always there, the output cap nearly always, and the effort levels for 41 % of
  entries. `reasoning` is absent entirely for models that do not reason and for
  routers, and a null `supported_efforts` means every level is accepted — so
  absent and null have to be told apart.
- The `reasoning` request object takes either `effort` or `max_tokens`, never
  both, plus `exclude` and `enabled`. An unsupported effort is moved to the
  nearest supported one. Reasoning is billed against `max_tokens`, so an
  exhausted budget returns `finish_reason: "length"` with empty content.
  [Reasoning tokens](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens.md)
- Reasoning comes back as `reasoning` and `reasoning_details` (types
  `reasoning.text`, `reasoning.summary`, `reasoning.encrypted`), and during
  tool calls those entries have to go back on the assistant message in their
  original order. (reasoning-tokens, above)
- Usage is always included; `usage.include` and `stream_options.include_usage`
  are deprecated no-ops, and a stream ends with a usage chunk before `[DONE]`.
  [Usage accounting](https://openrouter.ai/docs/guides/administration/usage-accounting.md),
  [Streaming](https://openrouter.ai/docs/api_reference/streaming.md)
- Errors are `{error: {code, message, metadata}}`. After the response has
  started they arrive inside the stream instead of as a status code, so a
  client has to look for a top-level `error` in every chunk. 402 carries
  `metadata.limit_source`, which distinguishes a low balance from a key limit.
  [Errors](https://openrouter.ai/docs/api_reference/errors-and-debugging.md),
  [Limits](https://openrouter.ai/docs/api_reference/limits.md)
- `POST /api/v1/messages` serves the Anthropic format as well, which the app
  does not need, since the OpenAI route is where the normalised model metadata
  lives.
  [Anthropic messages](https://openrouter.ai/docs/api/api-reference/anthropic-messages/create-a-message.md)

Endpoint: `baseUrl = https://openrouter.ai/api/v1`, `protocol = OPENAI_CHAT`,
`authScheme = BEARER`, `extraHeaders = {X-OpenRouter-Title, X-Title}`,
`checkPath = key`.

## Kiro

Kiro has no public HTTP API. The scheme below is
[Kiro Account Manager](https://github.com/chaogei/Kiro-account-manager)'s, an
Electron app whose API proxy talks to the backend the way Kiro IDE does
(checked against its `main` branch, `src/main/proxy/kiroApi.ts`,
`translator.ts` and `src/main/index.ts`, in October 2026). It is AGPL-3.0, so
`KiroCredential`, `KiroAuth`, `KiroAdapter`, `KiroWire` and `KiroToolLeak` are
written from its facts, not its code. Where it is silent, the facts come from
[kiro-gateway](https://github.com/jwadow/kiro-gateway) (AGPL, facts only) and
[9router](https://github.com/decolua/9router) (MIT). Treat all of it as
reverse engineered rather than documented, and expect it to move.

- The backend is Amazon Q Developer / CodeWhisperer. The surfaces Kiro's own
  desktop app and CLI use are `q.<region>.amazonaws.com` and
  `codewhisperer.<region>.amazonaws.com`, with the route
  `generateAssistantResponse`. The older `runtime.<region>.kiro.dev` path is
  deprecated and now answers 400.
  [9router issue #3774](https://github.com/decolua/9router/issues/3774)
- Responses are an AWS event-stream, not SSE, so this needs its own adapter
  rather than a tweak to the OpenAI one.
  [kiroxy architecture](https://github.com/nopperabbo/kiroxy/blob/main/docs/ARCHITECTURE.md),
  [kiro-reverse-api](https://github.com/tanu360/kiro-reverse-api/blob/main/README.md)
- The one thing the user has to supply is an account refresh token, which
  starts with `aorAAAAAG`. Account managers take exactly that and a region,
  defaulting to `us-east-1`.
  [OmniRoute Kiro setup](https://github.com/UmeshOnAI/OmniRoute-AI-Gateway/blob/main/docs/guides/KIRO_SETUP.md)
  The Kiro IDE keeps it in `~/.aws/sso/cache/kiro-auth-token.json`; a phone
  has no such file, so the token is pasted.
  [kiro-gateway](https://github.com/jwadow/kiro-gateway/blob/main/README.md)

How the app does it, following the manager:

- **Credential** (`KiroCredential`). The one field takes what the manager's
  import takes: the refresh token alone (`aorAAAAAG…`, a Google or GitHub
  sign-in); JSON `{refreshToken, clientId, clientSecret, region, authMethod,
  provider, profileArn}`, alone, in a list, or nested under `credentials` as
  the manager exports an account (Kiro IDE's own `kiro-auth-token.json` pastes
  as it is for a social sign-in); or its line format
  `email----password----refreshToken----clientId----clientSecret[----provider]`.
  It is stored compact, on one line. Anything else is a Kiro API key, sent as
  the bearer with `TokenType: API_KEY` and never exchanged (from 9router; the
  manager has no API keys).
- **Exchange.** A social token goes to
  `POST https://prod.us-east-1.auth.desktop.kiro.dev/refreshToken` with
  `{"refreshToken": …}` and Kiro IDE's user agent. A Builder ID or Identity
  Center token goes to `POST https://oidc.<region>.amazonaws.com/token` with
  `{clientId, clientSecret, refreshToken, grantType: "refresh_token"}`; Kiro
  IDE keeps that client in `~/.aws/sso/cache/<clientIdHash>.json`. Either
  answers `accessToken`, `expiresIn` (3600 s when absent; renewed 2 minutes
  early) and possibly a new `refreshToken`, which replaces the stored one.
- **Profile.** Every request names one. A social sign-in uses the one its
  exchange returns, else the profile all social sign-ins share,
  `arn:aws:codewhisperer:us-east-1:699475941385:profile/EHGA3GRVQMUK`; Builder
  ID uses Kiro IDE's fixed one,
  `arn:aws:codewhisperer:us-east-1:638616132270:profile/AAAACCCCXXXX`. An
  organisation's Identity Center (`provider: Enterprise`, or an external
  identity provider) is looked up once with
  `POST https://codewhisperer.<us-east-1|eu-central-1>.amazonaws.com/ListAvailableProfiles`
  and body `{}`, and kept with the credential. The manager also has a fixed
  fallback for organisations whose lookup fails; it is not documented as Kiro
  IDE's, so the app does not use it.
- **Generate.** `POST https://codewhisperer.us-east-1.amazonaws.com/generateAssistantResponse`
  first, then `https://q.us-east-1.amazonaws.com/generateAssistantResponse`,
  both routed by path (no `X-Amz-Target`) and in N. Virginia whatever the
  account's region. Any refusal but a 401 or 403 moves on to the next; a 401 or
  403 means the token, so a refresh token is exchanged once more and the pair
  is tried again.
- **Headers**, as the manager sends Kiro IDE's: `Authorization: Bearer`,
  `User-Agent: aws-sdk-js/1.0.34 ua/2.1 os/linux#<kernel> lang/js md/nodejs#22.22.0 api/codewhispererstreaming#1.0.34 m/E KiroIDE-0.12.155-<machineId>`,
  `x-amz-user-agent: aws-sdk-js/1.0.34 KiroIDE 0.12.155 <machineId>`,
  `x-amzn-kiro-agent-mode: vibe`, a fresh `amz-sdk-invocation-id`,
  `amz-sdk-request: attempt=1; max=3`, and `TokenType: EXTERNAL_IDP` for an
  external identity provider. The machine id is the SHA-256 of
  `kiro-device-<connection id>`, which is how the manager derives one for an
  account without a bound id. One addition: `x-amzn-codewhisperer-optout: true`
  on every call, which the manager sends on listings only; it asks that the
  conversation not be used to improve the service.
- **Models.** `GET https://q.<us-east-1|eu-central-1>.amazonaws.com/ListAvailableModels?origin=AI_EDITOR&maxResults=50&profileArn=…`,
  paged by `nextToken` (at most 10 pages), answers with the account's own
  list: `modelId`, `modelName`, `description`, `rateMultiplier`,
  `supportedInputTypes`, `tokenLimits.{maxInputTokens, maxOutputTokens}` and
  `additionalModelRequestFieldsSchema`. What an account sees depends on its
  tier. A model that reports no window is taken at 200 000 tokens.
- **Reasoning.** The request-fields schema says the shape: a model whose schema
  has `output_config.effort.enum` gets `thinking: {type: "adaptive", display:
  "summarized"}` with `output_config.effort`; one with `reasoning.effort.enum`
  gets `reasoning.effort`. The levels are that enum's, and a level the model
  does not list becomes its highest. Without a schema, Claude 4.6 and later
  take the first shape (4.6 stops at `max`, without `xhigh`) and the GPT-5.6
  family the second.
- **Body.** `conversationState` with `agentContinuationId` (new per request),
  `agentTaskType: vibe`, `chatTriggerType: MANUAL`, a `conversationId` that
  stays the same for one chat, `history` (left out when empty), and
  `currentMessage.userInputMessage`; next to it `profileArn`,
  `inferenceConfig.{maxTokens, temperature, topP}` and
  `additionalModelRequestFields`. Every user turn carries `content`, `modelId`
  and `origin: AI_EDITOR`, plus `images` and
  `userInputMessageContext.{tools, toolResults}`; tools go on the current
  message only. There is no top-level system prompt: it is the first turn of
  the history, answered "I will follow these instructions.", as Kiro IDE sends
  it. Reasoning is not sent back, since `reasoningContent` in the history is
  refused as malformed. No turn is empty: a turn of tool calls alone says " ",
  a reply with nothing in it "I understand.", an empty question "Continue".
  Tool results are always `status: success`, a failure saying so in its text,
  as the manager sends them. The app asks for at most 32 000 tokens of answer.
- **Stream.** Besides text (`assistantResponseEvent`, `codeEvent`), reasoning
  and tool calls, it ends with three events, as seen live (October 2026):
  `metadataEvent` with the stop reason and nothing else,
  `contextUsageEvent` with how full the window is in percent, and
  `meteringEvent` with what the request cost in credits. There are no token
  counts, cached or not. The decoder still reads `tokenUsage`, `usageEvent`
  and `metricsEvent` in case they come, but does not turn the percentage
  into a prompt count: it is a share of a window the listing does not always
  state, it includes the answer, and the token estimator is calibrated
  against whatever count arrives. Credits and the percentage are logged per
  request. `invalidStateEvent` is said in the reply as a warning, and
  `supplementaryWebLinksEvent` and `citationEvent` links are listed at its
  end. An `error` inside an event's payload fails the turn.
- **Tool input arrives late.** A call's name and its short arguments (a
  path) come at once; a long string value (a file's content) comes only when
  the model has finished writing it, all at once: 37 seconds of nothing,
  then 7 KB in 1.5 s, for one `fs_write`. The tool card therefore shows how
  long the model has been writing as well as how much has arrived, and the
  path from the first fragments.
- **Calls written as text.** The backend sometimes passes a tool call through
  as the XML it was written in (`<function_calls><invoke name="…"><parameter
  name="…">…</parameter></invoke>`, the opener sometimes mangled into a bare
  `count`), inside text and split across events. The manager catches these
  and makes the calls; so does `KiroToolLeak`, for tools the request declares
  only, and not when the same call came structured as well.
- **Errors.** `CONTENT_LENGTH_EXCEEDS_THRESHOLD`, or a 400 to a body over
  600 000 bytes, is read as context overflow and compacted;
  `MONTHLY_REQUEST_COUNT` as a used-up monthly quota; `INVALID_MODEL_ID` as a
  model the account does not have.
- **The sharp edge.** The Kiro IDE holds the same refresh token. An exchange
  here that rotates it leaves the IDE with a dead one, and the IDE signs out;
  an exchange in the IDE does the same to the app, whose next request then asks
  for the current token. The form says so before a token is pasted.
- **Prompt caching.** The service caches a repeated prefix itself; what a
  client controls is whether its prefix repeats. Measured with
  claude-opus-5, one agent turn of five requests (a 55 KB file read, three
  commands, the answer), the cost of each in credits from `meteringEvent`:

  | | 1 | 2 | 3 | 4 | 5 |
  |---|---|---|---|---|---|
  | stable system turn, cache points | 0.139* | 0.245 | 0.245 | 0.147 | 0.135 |
  | stable system turn, no points | 0.227 | 0.245 | 0.245 | 0.147 | 0.135 |
  | changing system turn, cache points | 0.225 | 0.244 | 0.245 | 0.246 | 0.234 |

  \* the system prefix was still cached from the run before.

  Content is cheap from the second request after it was first sent. With the
  window-fill line inside the system turn every request is a cold one, about
  40% dearer by the end of the turn; with it on the current message
  (`SystemPrompt.Parts`, `<app-context>`) the prefix repeats. The cache points
  changed nothing measurable. They stay, `cachePoint: {type: "default"}` on
  the system turn and on the last user turn of the history, as the manager
  sends them for a client's `cache_control`, for models whose listing entry
  says they cache (`promptCaching: {supportsPromptCaching,
  maximumCacheCheckpointsPerRequest, minimumTokensPerCacheCheckpoint}`); no
  request carrying them was refused (`KiroPromptCache`).
- **Not taken over.** The manager's account pool, its own prompt additions and
  the sign-in flows themselves (browser login, SSO-cookie import); the app
  takes a token that already exists.
- None of this is endorsed by AWS, and it is the user's own subscription being
  used from a different client. The connection form says so.

Endpoint: `baseUrl = https://q.us-east-1.amazonaws.com` (the listing's host;
requests go to the two surfaces above), `protocol = KIRO`, `authScheme =
BEARER`. The auth scheme only says a credential is needed: the adapter
exchanges it rather than `Http` sending it as it is. Key prefix `aorAAAAAG`,
or a structured sign-in as above.

## Custom

`POST {base}/chat/completions` is what every local server speaks — vLLM,
llama.cpp, Ollama, LM Studio, and the proxies. The Anthropic route is less
common: Ollama from 0.14 and LM Studio have it, vLLM and llama.cpp generally do
not.
[Ollama Anthropic compatibility](https://docs.ollama.com/api/anthropic-compatibility),
[LM Studio](https://lmstudio.ai/docs/developer/anthropic-compat/messages)

This is the only scheme that asks for a URL and a format, because neither can
be inferred. A local server usually needs no credential at all, and
`NetworkHosts` permits plain HTTP to a private address while refusing it across
the internet. The auth scheme follows from the form rather than being asked
for: no key means `AuthScheme.NONE`, a key for an Anthropic-format server goes
in `x-api-key` (`X_API_KEY`), and any other key is a bearer token. Extra
headers stay available here, for proxies that want one.

Content from third-party pages above is paraphrased.
