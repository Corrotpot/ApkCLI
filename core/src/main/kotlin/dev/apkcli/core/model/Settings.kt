package dev.apkcli.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which wire protocol an endpoint speaks.
 *
 * "OpenAI-compatible" is the lingua franca — vLLM, llama.cpp's server,
 * LM Studio, Ollama, OpenRouter, Together, Groq, Mistral and most proxies all
 * expose it — so it is the default. The others exist because their reasoning
 * and tool-calling semantics differ in ways that cannot be papered over:
 * Anthropic needs `thinking` blocks echoed back with their signatures, and the
 * Responses API carries reasoning as server-side state keyed by item id.
 */
@Serializable
enum class Protocol {
    /**
     * `POST {base}/chat/completions`
     *
     * Covers OpenRouter, OpenAI, vLLM, SGLang, llama.cpp's server, Ollama,
     * LM Studio, and essentially every proxy and gateway.
     */
    @SerialName("openai_chat") OPENAI_CHAT,

    /**
     * `POST {base}/messages` with an `anthropic-version` header.
     *
     * Exists separately because reasoning here is a token budget and the
     * returned `thinking` blocks carry signatures that must be echoed back
     * verbatim on the following turn — semantics that cannot be expressed in
     * the chat protocol.
     */
    @SerialName("anthropic_messages") ANTHROPIC_MESSAGES,

    /**
     * Kiro's backend, Amazon Q Developer: `generateAssistantResponse` on
     * `codewhisperer.<region>.amazonaws.com`, answering in an AWS event-stream
     * rather than SSE. The credential is an account refresh token, exchanged
     * for an access token by the adapter. Only the Kiro scheme uses it.
     */
    @SerialName("kiro") KIRO,
    ;

    val label: String
        get() = when (this) {
            OPENAI_CHAT -> "OpenAI-compatible (/chat/completions)"
            ANTHROPIC_MESSAGES -> "Anthropic Messages (/messages)"
            KIRO -> "Kiro (generateAssistantResponse)"
        }
}

/*
 * Not implemented, on purpose: the OpenAI Responses API (`POST /responses`).
 *
 * It would be a third adapter with its own reasoning-item and server-side-state
 * model, and nothing currently needs it — OpenAI still serves
 * /chat/completions, and OpenRouter normalises everything upstream of it. A
 * selectable protocol with no working adapter is worse than an absent one, so
 * it is left out until something actually requires it.
 */

/** How the API key is presented. Proxies and self-hosted servers vary. */
@Serializable
enum class AuthScheme {
    @SerialName("bearer") BEARER,

    /** `x-api-key: <key>`, which is what Anthropic uses. */
    @SerialName("x_api_key") X_API_KEY,

    @SerialName("custom_header") CUSTOM_HEADER,

    /** No credential at all — a local llama.cpp or Ollama server. */
    @SerialName("none") NONE,
}

/**
 * A user-configured endpoint.
 *
 * The API key is **not** stored here. This object is persisted as plain JSON;
 * the key lives in `SecretStore`, encrypted with a hardware-backed Keystore
 * key, and is looked up by [id] at request time.
 */
@Serializable
data class Endpoint(
    val id: String,
    val name: String,
    /** Base URL without a trailing slash, e.g. `https://api.example.com/v1`. */
    val baseUrl: String,
    val protocol: Protocol = Protocol.OPENAI_CHAT,
    val authScheme: AuthScheme = AuthScheme.BEARER,
    /** Header name when [authScheme] is [AuthScheme.CUSTOM_HEADER]. */
    val authHeaderName: String = "Authorization",
    /** Extra headers; some proxies require routing or attribution headers. */
    val extraHeaders: Map<String, String> = emptyMap(),
    /**
     * The connection scheme this endpoint was made from, by
     * `ConnectionScheme.id`: a known service, whose URL, format and headers
     * are the scheme's and re-applied on every read, or `custom`, where they
     * are the user's. Null in settings written before schemes existed; the
     * URL decides then.
     */
    val scheme: String? = null,

    /**
     * Models offered by this endpoint.
     *
     * Populated either by querying `/models` or typed in by hand, because
     * plenty of gateways do not implement a listing route.
     */
    val models: List<ModelSpec> = emptyList(),
    val defaultModelId: String? = null,

    val timeoutSeconds: Int = 180,
    /** Streaming responses can idle during long reasoning phases. */
    val streamIdleTimeoutSeconds: Int = 300,
    val maxRetries: Int = 2,

    /** Send `stream: true`. Off for backends with broken SSE. */
    val streaming: Boolean = true,

    val enabled: Boolean = true,
    val createdAt: Long = 0,
    val lastTestedAt: Long = 0,
    val lastTestResult: String = "",
) {
    val displayUrl: String get() = baseUrl.removeSuffix("/")

    fun modelById(id: String?): ModelSpec? = models.firstOrNull { it.id == id }

    val defaultModel: ModelSpec?
        get() = modelById(defaultModelId) ?: models.firstOrNull()
}

/**
 * What a model's prompt caching allows, as its listing reports it.
 *
 * Caching is not automatic anywhere it exists: the client marks points in the
 * prompt, and the service reuses everything before a point if it matches what
 * came last time. A point below [minimumTokens] is not worth taking, and more
 * than [checkpoints] of them is an error.
 */
@Serializable
data class PromptCaching(
    /** How many points one request may carry. Amazon's services allow four. */
    val checkpoints: Int = 4,
    /** Below this many tokens before a point, the service will not cache it. */
    val minimumTokens: Int = 1_024,
)

/**
 * What a model can do and what it costs us in context.
 *
 * [contextWindow] is the number the whole budgeting system hangs off, so it is
 * user-editable: a gateway rarely reports it, and getting it wrong by an order
 * of magnitude either wastes most of the window or triggers a 400 mid-session.
 */
@Serializable
data class ModelSpec(
    val id: String,
    val displayName: String = id,
    /** Total prompt + completion tokens the model accepts. */
    val contextWindow: Int = 128_000,
    /** Cap on a single response. Subtracted from the usable prompt budget. */
    val maxOutputTokens: Int = 8_192,
    val supportsTools: Boolean = true,
    val supportsStreaming: Boolean = true,
    val supportsImages: Boolean = false,
    /** Whether the model exposes reasoning, and in which dialect. */
    val reasoning: ReasoningSupport = ReasoningSupport.NONE,
    /**
     * Reasoning levels the endpoint says this model accepts, in its own
     * spelling (`none`, `low`, `medium`, `high`, `xhigh`, `max`…), lowest
     * first. Empty when the endpoint does not publish them — and then none are
     * offered: a level the model does not take is a 400, not a preference.
     * See [ReasoningLevels].
     */
    val reasoningEfforts: List<String> = emptyList(),
    /** The level the model uses when none is sent, as the endpoint reports it. */
    val defaultReasoningEffort: String? = null,
    /** The model always reasons; there is no "off". */
    val reasoningMandatory: Boolean = false,
    /**
     * The `thinking.type` values the model accepts — `adaptive`, `enabled`,
     * `disabled` — where its listing says (Anthropic's Models API does).
     * Empty when it does not. Sending one the model does not take is a 400:
     * Claude 4.7 and later refuse `enabled`, several refuse `disabled`.
     */
    val thinkingTypes: List<String> = emptyList(),
    /**
     * What the endpoint will cache of a prompt, when it says. Null means it
     * does not cache, or has not said so. See `KiroPromptCache`.
     */
    val caching: PromptCaching? = null,
    /**
     * True when the endpoint actually reported the context window.
     *
     * False means [contextWindow] is a fallback and the UI should ask the user
     * to confirm it, because every budgeting and compaction decision is derived
     * from this number.
     */
    val metadataFromEndpoint: Boolean = false,
    /**
     * Set when the user edited this spec by hand.
     *
     * Refreshing the model list must not overwrite a value someone corrected
     * deliberately — that is how a hand-fixed 1M window silently reverts to a
     * default and long sessions start failing again.
     */
    val userEdited: Boolean = false,
    /** Informational only; shown in the model picker. */
    val notes: String = "",
    /**
     * A window the endpoint revealed by refusing a prompt as too long, when
     * that is smaller than what its listing claims. A refreshed listing must
     * not raise the window past it again — Kiro, for one, caps a request's
     * size well below the window its models list.
     */
    val learnedContextWindow: Int? = null,
) {
    /** Usable prompt budget, before any safety reserve. */
    val promptBudget: Int get() = (contextWindow - maxOutputTokens).coerceAtLeast(1024)
}

/**
 * Which reasoning dialect a model speaks.
 *
 * Four incompatible shapes exist across the OpenAI-compatible family, and
 * sending the wrong one is a hard 400 on strict gateways. The value is detected
 * from the endpoint's own `/models` metadata — see [dev.apkcli.core.llm.ModelDiscovery].
 */
@Serializable
enum class ReasoningSupport {
    @SerialName("none") NONE,

    /** `reasoning_effort: low|medium|high` — OpenAI proper and close clones. */
    @SerialName("effort") EFFORT,

    /**
     * `reasoning: { effort, max_tokens, exclude }` plus `include_reasoning` —
     * OpenRouter's unified form, which it translates per upstream provider.
     */
    @SerialName("openrouter") OPENROUTER_UNIFIED,

    /** `thinking: { type: enabled, budget_tokens: N }` — Anthropic, up to Claude 4.5. */
    @SerialName("budget") BUDGET,

    /**
     * `thinking: { type: adaptive }` with the level in `output_config.effort`
     * — Anthropic from Claude 4.6 on, where the model decides how much to
     * think and `budget_tokens` is deprecated or refused.
     */
    @SerialName("adaptive") ADAPTIVE,
}

/**
 * How hard the model should think, expressed once and translated per protocol.
 *
 * Exposed in the UI as a single control because the user should not have to
 * know whether their endpoint wants an effort string or a token budget.
 */
@Serializable
enum class ThinkingMode {
    @SerialName("off") OFF,
    @SerialName("brief") BRIEF,
    @SerialName("balanced") BALANCED,
    @SerialName("deep") DEEP,

    /**
     * Let the agent decide per turn based on what it is about to do: a one-line
     * edit gets none, a design question or a second failure of the same
     * approach gets DEEP.
     */
    @SerialName("auto") AUTO,
    ;

    val label: String
        get() = when (this) {
            OFF -> "Off"
            BRIEF -> "Brief"
            BALANCED -> "Balanced"
            DEEP -> "Deep"
            AUTO -> "Auto"
        }

    /** Effort string for protocols that take one. */
    fun effort(): String? = when (this) {
        OFF -> null
        BRIEF -> "low"
        BALANCED -> "medium"
        DEEP -> "high"
        AUTO -> "medium"
    }

    /** Token budget for protocols that take one, scaled to the model's window. */
    fun budgetTokens(maxOutput: Int): Int? = when (this) {
        OFF -> null
        BRIEF -> (maxOutput / 8).coerceIn(1_024, 4_096)
        BALANCED -> (maxOutput / 3).coerceIn(2_048, 16_384)
        DEEP -> (maxOutput * 2 / 3).coerceIn(4_096, 48_000)
        AUTO -> (maxOutput / 3).coerceIn(2_048, 16_384)
    }
}

/**
 * Reasoning levels as endpoints publish them per model (OpenRouter's
 * `reasoning.supported_efforts`, and the like). Nothing here invents a level:
 * the list shown and sent is always the model's own.
 */
object ReasoningLevels {

    /** Low to high, for display. Unknown spellings sort after these, in the endpoint's order. */
    val ORDER = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

    /** Off. */
    const val NONE = "none"

    /** Key of a model's chosen level in [AppSettings.reasoningEfforts]. */
    fun key(endpointId: String, modelId: String): String = "$endpointId|$modelId"

    /** Normalise and order a published list. */
    fun normalize(levels: Collection<String>): List<String> {
        val cleaned = levels
            .map { it.trim().lowercase() }
            .filter { LEVEL.matches(it) }
            .distinct()
        val known = ORDER.filter { it in cleaned }
        return known + cleaned.filter { it !in ORDER }
    }

    /**
     * The level a request should carry: the user's pick when the model still
     * offers it; else the offered level nearest to it; else the default the
     * endpoint names. Null — send nothing, the model decides — when the
     * endpoint publishes no levels, or nothing was picked and it names no
     * default: a level of our choosing would be a guess sent as a fact.
     */
    fun effective(model: ModelSpec, chosen: String?): String? {
        val offered = model.reasoningEfforts
        if (offered.isEmpty()) return null
        if (chosen != null && chosen in offered) return chosen
        if (chosen != null) {
            val rank = ORDER.indexOf(chosen)
            if (rank >= 0) {
                offered
                    .filter { it in ORDER }
                    .minByOrNull { level -> kotlin.math.abs(ORDER.indexOf(level) - rank) * 2 + if (ORDER.indexOf(level) > rank) 1 else 0 }
                    ?.let { return it }
            }
        }
        return model.defaultReasoningEffort?.takeIf { it in offered }
    }

    /** The prompt's reasoning guidance for a level; null leaves the agent's own setting. */
    fun thinkingModeFor(level: String?): ThinkingMode? = when (level) {
        null -> null
        NONE -> ThinkingMode.OFF
        "minimal", "low" -> ThinkingMode.BRIEF
        "medium" -> ThinkingMode.BALANCED
        else -> ThinkingMode.DEEP
    }

    private val LEVEL = Regex("^[a-z][a-z0-9_-]{0,15}$")
}

/** Per-tool gate. The agent's blast radius is configured here, not in prose. */
@Serializable
enum class ToolPermission {
    /** Run without asking. */
    @SerialName("allow") ALLOW,

    /** Ask the user every time, showing the arguments. */
    @SerialName("ask") ASK,

    /** Refuse, and tell the model it is unavailable so it stops trying. */
    @SerialName("deny") DENY,
}

/**
 * A kind of action the From Rules mode can let through without asking, as
 * the user ticks it in Settings › Rules. Each is a group of tools; a tool in
 * none of them asks. Reads never ask in any mode and are in none.
 *
 * [label] is how the system prompt names the group to the model.
 */
@Serializable
enum class ApprovalRule(val tools: Set<String>, val label: String) {
    @SerialName("edit")
    EDIT(setOf("fs_write", "fs_edit", "fs_multi_edit", "fs_apply_patch"), "writing and editing files"),

    @SerialName("delete")
    DELETE(setOf("fs_delete", "fs_move", "revert_edit"), "deleting and moving files, reverting edits"),

    @SerialName("commands")
    COMMANDS(setOf("sh_exec", "build_run", "test_run"), "running commands, builds and tests"),

    @SerialName("background")
    BACKGROUND(setOf("sh_background", "sh_job_kill"), "starting and stopping background jobs"),

    @SerialName("packages")
    PACKAGES(setOf("pkg_install", "pkg_remove"), "installing and removing packages"),

    @SerialName("subagents")
    SUBAGENTS(setOf("subagent_dispatch"), "handing tasks to subagents"),
    ;

    companion object {
        /** The group [tool] belongs to, or null for one in none. */
        fun of(tool: String): ApprovalRule? = entries.firstOrNull { tool in it.tools }

        /** What a fresh install lets through: file edits, which every change list can take back, and subagents. */
        val DEFAULT: Set<ApprovalRule> = setOf(EDIT, SUBAGENTS)
    }
}

/**
 * An agent: a named bundle of model, prompt additions, tool access and
 * autonomy.
 *
 * The user picks one of three modes, which are agents that differ in when
 * they ask: Confirm ([Autonomy.SUPERVISED]), From Rules ([Autonomy.RULES])
 * and Bypass Mode ([Autonomy.AUTOPILOT]). The read-only Explorer is
 * [hidden]: it exists for subagents dispatched to investigate.
 *
 * Subagents are the reason this is a first-class object. A "reviewer" agent
 * with read-only tools and a cheap model can be dispatched by the main agent
 * without risking writes, and its context is separate — which is how a long
 * investigation stays out of the main window.
 */
@Serializable
data class AgentDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    /** Appended to the base system prompt. */
    val instructions: String = "",
    val endpointId: String? = null,
    val modelId: String? = null,
    val thinkingMode: ThinkingMode = ThinkingMode.AUTO,

    /** Null means "every registered tool". */
    val allowedTools: Set<String>? = null,
    val toolPermissions: Map<String, ToolPermission> = emptyMap(),

    val autonomy: Autonomy = Autonomy.SUPERVISED,

    /** Hard stop on agent loop iterations, so a loop cannot run forever. */
    val maxIterations: Int = 60,
    /** Tool calls the agent may run concurrently in one turn. */
    val maxParallelTools: Int = 3,

    /** Can this agent dispatch subagents? */
    val canDispatchSubagents: Boolean = true,
    val builtIn: Boolean = false,
    val icon: String = "",
    /**
     * Under [Autonomy.RULES], the kinds of action that go ahead without
     * asking. The built-in From Rules agent takes them from
     * [AppSettings.approvalRules] every time settings are read.
     */
    val autoApproved: Set<ApprovalRule> = emptySet(),
    /** Not offered as a mode: an agent for subagents only (the Explorer). */
    val hidden: Boolean = false,
) {
    @Serializable
    enum class Autonomy {
        /**
         * Confirm: asks before every change — a file written, a command run,
         * a package installed. Calls that change nothing (reads, searches,
         * listings) go ahead; see ToolRegistry.execute.
         */
        @SerialName("supervised") SUPERVISED,

        /** From Rules: asks about a change unless its kind is in [autoApproved]. */
        @SerialName("rules") RULES,

        /** Bypass Mode: asks about nothing and runs to completion. */
        @SerialName("autopilot") AUTOPILOT,

        /** Never writes, never executes. Investigation only. */
        @SerialName("read_only") READ_ONLY,
        ;

        /** The mode's own name: the same in every language, as the user named them. */
        val label: String
            get() = when (this) {
                SUPERVISED -> "Confirm"
                RULES -> "From Rules"
                AUTOPILOT -> "Bypass Mode"
                READ_ONLY -> "Read-only"
            }
    }

    fun permissionFor(tool: String): ToolPermission {
        if (allowedTools != null && tool !in allowedTools) return ToolPermission.DENY
        toolPermissions[tool]?.let { return it }
        return when (autonomy) {
            // What the allowlist names, and nothing else. This used to deny
            // even those — so the Explorer, and every subagent dispatched to
            // investigate, was declared no tools at all and could only guess.
            // Without an allowlist nothing is known to be read-only: deny. And
            // an allowlist cannot smuggle a write in: read-only agents are
            // auto-approved, so the read-only set is the hard limit.
            Autonomy.READ_ONLY ->
                if (allowedTools != null && tool in dev.apkcli.core.agent.Tools.readOnlyToolNames) {
                    ToolPermission.ALLOW
                } else {
                    ToolPermission.DENY
                }
            Autonomy.AUTOPILOT -> ToolPermission.ALLOW
            Autonomy.RULES ->
                if (ApprovalRule.of(tool)?.let { it in autoApproved } == true) ToolPermission.ALLOW else ToolPermission.ASK
            Autonomy.SUPERVISED -> ToolPermission.ASK
        }
    }
}

/** Everything persisted in preferences. */
@Serializable
data class AppSettings(
    val endpoints: List<Endpoint> = emptyList(),
    val activeEndpointId: String? = null,
    val activeModelId: String? = null,
    val agents: List<AgentDefinition> = emptyList(),
    val activeAgentId: String? = null,
    /** What the From Rules mode lets through without asking: Settings › Rules. */
    val approvalRules: Set<ApprovalRule> = ApprovalRule.DEFAULT,

    val thinkingMode: ThinkingMode = ThinkingMode.AUTO,
    /**
     * The reasoning level picked for each model, keyed by
     * [ReasoningLevels.key]. Only levels the model itself published; absent
     * means the model's own default.
     */
    val reasoningEfforts: Map<String, String> = emptyMap(),
    /** Show reasoning in the transcript. Off hides it but still accounts for it. */
    val showThinking: Boolean = true,

    val context: ContextSettings = ContextSettings(),
    val editor: EditorSettings = EditorSettings(),

    /** Index the workspace in the background for symbol search. */
    val backgroundIndexing: Boolean = true,
    /** Only index and build while charging. */
    val heavyWorkOnChargeOnly: Boolean = false,

    val remoteNode: RemoteNodeSettings? = null,
    val search: SearchSettings = SearchSettings(),

    val telemetryOptIn: Boolean = false,

    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    companion object {
        /**
         * 2: the shipped endpoints already offered are recorded, so a deleted
         * one is not added back.
         *
         * 3: endpoints record their [Endpoint.scheme], and AnyModel is no
         * longer shipped: a seeded AnyModel nobody used is removed once.
         *
         * 4: no endpoint is shipped. The OpenRouter earlier versions created
         * is removed once if it never got a key, and the record of what was
         * offered, with nothing left to offer, is gone (an old blob's copy is
         * ignored on read).
         */
        const val CURRENT_SCHEMA_VERSION = 4
    }

    val activeEndpoint: Endpoint?
        get() = endpoints.firstOrNull { it.id == activeEndpointId } ?: endpoints.firstOrNull { it.enabled }

    val activeAgent: AgentDefinition?
        get() = agents.firstOrNull { it.id == activeAgentId } ?: agents.firstOrNull { !it.hidden } ?: agents.firstOrNull()

    val activeModel: ModelSpec?
        get() = activeEndpoint?.let { ep -> ep.modelById(activeModelId) ?: ep.defaultModel }

    /** The reasoning level a request to [model] on [endpointId] carries; null sends none. */
    fun reasoningEffortFor(endpointId: String, model: ModelSpec): String? =
        ReasoningLevels.effective(model, reasoningEfforts[ReasoningLevels.key(endpointId, model.id)])
}

@Serializable
data class ContextSettings(
    /** Fraction of the prompt budget at which background pre-summarising starts. */
    val softThreshold: Float = 0.75f,
    /** Fraction at which compaction is forced before the next request. */
    val hardThreshold: Float = 0.90f,
    /** Turns always kept verbatim, however full the window is. */
    val keepRecentTurns: Int = 6,
    /** Tool results larger than this are spilled to a blob and summarised. */
    val toolResultTokenCap: Int = 4_000,
    /** Archived messages re-injected per turn by FTS recall. 0 disables it. */
    val recallHits: Int = 4,
    /** Reserve left unused, as a safety margin against estimator error. */
    val safetyReserveTokens: Int = 2_000,
    /** Keep `AGENTS.md` / project memory in every request. */
    val includeProjectMemory: Boolean = true,
)

@Serializable
data class EditorSettings(
    val fontSizeSp: Float = 13f,
    val tabSize: Int = 4,
    val useSpaces: Boolean = true,
    val wordWrap: Boolean = false,
    val showLineNumbers: Boolean = true,
    val theme: String = "apkcli-dark",
    val dynamicColor: Boolean = true,
    val monospaceChat: Boolean = false,
)

/** Where `web_search` gets its results. */
@Serializable
enum class SearchMode {
    /**
     * DuckDuckGo's no-JavaScript results page, read over plain HTTPS. No
     * account and no key, so search works on a fresh install. The default.
     */
    @SerialName("keyless") KEYLESS,

    /** A JSON search API the user points at: Brave, Tavily, SearXNG. */
    @SerialName("custom") CUSTOM,

    /** Not offered to the model at all. */
    @SerialName("off") OFF,
}

/**
 * Web search.
 *
 * Keyless by default. A custom JSON endpoint remains available for anyone who
 * wants a commercial index or a self-hosted SearXNG. When search is off — or set
 * to custom with no URL — the `web_search` tool is not registered at all:
 * declaring a tool that always fails costs tokens on every request and invites
 * the model to keep trying it.
 *
 * A custom endpoint's credential lives in `SecretStore` under [CREDENTIAL_ID].
 */
@Serializable
data class SearchSettings(
    val mode: SearchMode = SearchMode.KEYLESS,
    /** Custom mode: e.g. `https://api.search.brave.com/res/v1/web/search`. */
    val url: String = "",
    /**
     * Custom mode: the header the key goes in. Brave wants
     * `X-Subscription-Token`, Tavily wants `Authorization` (sent as a bearer
     * token), SearXNG wants nothing.
     */
    val authHeaderName: String = "X-Subscription-Token",
    /**
     * Keyless mode: DuckDuckGo region, e.g. `ru-ru` or `us-en`. `wt-wt` means
     * no regional bias.
     */
    val region: String = "wt-wt",
) {
    /** Whether `web_search` is offered to the model. */
    val configured: Boolean
        get() = when (mode) {
            SearchMode.KEYLESS -> true
            SearchMode.CUSTOM -> url.isNotBlank()
            SearchMode.OFF -> false
        }

    companion object {
        /** Pseudo-endpoint id, so the search key reuses the encrypted store. */
        const val CREDENTIAL_ID = "__search__"
    }
}

/**
 * Optional companion daemon on a desktop machine.
 *
 * Honest about the limits of the device: a Gradle build or a Rust compile on a
 * phone is minutes of sustained full-core load, which thermally throttles
 * everything else. When a node is configured, `build_run` and `test_run` are
 * forwarded to it and only the output comes back. The phone stays an editor and
 * an agent host, which is what it is good at.
 */
@Serializable
data class RemoteNodeSettings(
    val name: String = "desktop",
    /** `ws://host:port` for the companion daemon. */
    val url: String = "",
    val token: String = "",
    /** Path on the remote machine that mirrors the local workspace. */
    val remoteWorkspace: String = "",
    val enabled: Boolean = false,
    /** Forward builds and tests even when the device is cool. */
    val preferForHeavyWork: Boolean = true,
    val syncBeforeRun: Boolean = true,
)
