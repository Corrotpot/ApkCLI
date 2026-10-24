package dev.apkcli.core.store

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.apkcli.core.agent.Tools
import dev.apkcli.core.llm.ConnectionScheme
import dev.apkcli.core.llm.EndpointDefaults
import dev.apkcli.core.llm.ModelDiscovery
import dev.apkcli.core.model.AgentDefinition
import dev.apkcli.core.model.AppSettings
import dev.apkcli.core.model.Endpoint
import dev.apkcli.core.model.ModelSpec
import dev.apkcli.core.model.ReasoningLevels
import dev.apkcli.core.model.ReasoningSupport
import dev.apkcli.core.model.SearchSettings
import dev.apkcli.core.model.ThinkingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "apkcli_settings",
)

/**
 * Persisted configuration.
 *
 * Stored as one JSON blob rather than a key per field. Settings here are
 * deeply nested (endpoints each contain a model list; agents contain a
 * permission map), and flattening that into preference keys means
 * hand-maintaining a migration for every shape change. One blob plus
 * `ignoreUnknownKeys` plus defaults on every field gives forwards and backwards
 * compatibility for free.
 *
 * API keys are *not* in here — see [SecretStore]. [hasKey] says whether one
 * is stored for an endpoint id, for the one migration that depends on it.
 */
class SettingsRepository(
    private val context: Context,
    private val hasKey: (endpointId: String) -> Boolean,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        val raw = prefs[KEY_SETTINGS]
        if (raw.isNullOrBlank()) {
            defaults()
        } else {
            runCatching { json.decodeFromString(AppSettings.serializer(), raw) }
                .getOrElse {
                    // Corrupt or incompatible: fall back rather than leave the
                    // user with an app that cannot start.
                    Log.e(TAG, "settings unreadable, using defaults", it)
                    defaults()
                }
                .migrated()
                .withBuiltInAgents()
        }
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            // Migrated before the transform, not only on read: the transform
            // has to act on what the screens showed, and the blob written
            // has to say the migrations ran.
            val existing = prefs[KEY_SETTINGS]
                ?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() }
                ?.migrated()
                ?: defaults()
            val next = transform(existing)
            prefs[KEY_SETTINGS] = json.encodeToString(AppSettings.serializer(), next)
        }
    }

    /**
     * Every migration, oldest first, then the version that records they ran.
     * Each one reads the version the blob was written with, so the version
     * is raised last, and never lowered.
     */
    private fun AppSettings.migrated(): AppSettings {
        val migrated = withSchemes().withoutUnusedFirstRun(hasKey)
        return if (migrated.schemaVersion >= AppSettings.CURRENT_SCHEMA_VERSION) {
            migrated
        } else {
            migrated.copy(schemaVersion = AppSettings.CURRENT_SCHEMA_VERSION)
        }
    }

    /* ------------------------------------------------------------ endpoints */

    suspend fun upsertEndpoint(endpoint: Endpoint) = update { settings ->
        val existing = settings.endpoints.indexOfFirst { it.id == endpoint.id }
        val endpoints = settings.endpoints.toMutableList()
        if (existing >= 0) endpoints[existing] = endpoint else endpoints += endpoint
        settings.copy(
            endpoints = endpoints,
            // First endpoint added becomes active, so the app is usable without
            // a second trip to settings.
            activeEndpointId = settings.activeEndpointId ?: endpoint.id,
            activeModelId = settings.activeModelId ?: endpoint.defaultModelId,
        )
    }

    suspend fun removeEndpoint(endpointId: String) = update { settings ->
        val endpoints = settings.endpoints.filterNot { it.id == endpointId }
        settings.copy(
            endpoints = endpoints,
            activeEndpointId = settings.activeEndpointId
                ?.takeIf { it != endpointId }
                ?: endpoints.firstOrNull()?.id,
        )
    }

    suspend fun setActiveEndpoint(endpointId: String, modelId: String? = null) = update { settings ->
        val endpoint = settings.endpoints.firstOrNull { it.id == endpointId }
        settings.copy(
            activeEndpointId = endpointId,
            activeModelId = modelId ?: endpoint?.defaultModelId ?: endpoint?.models?.firstOrNull()?.id,
        )
    }

    suspend fun setActiveModel(modelId: String) = update { it.copy(activeModelId = modelId) }

    /**
     * Merge a freshly fetched model list into an endpoint.
     *
     * Three-way precedence, because both "always keep the old value" and
     * "always take the new one" are wrong:
     *
     *  1. A spec the user edited wins. Someone who corrected a context window
     *     by hand should not have it reverted by a refresh.
     *  2. Otherwise, metadata the endpoint actually reported wins. OpenRouter
     *     and vLLM publish real numbers, and those should track upstream
     *     changes such as a provider raising its output cap.
     *  3. Otherwise keep what we had, which may have been learned from an
     *     overflow error.
     *
     * Models that vanished from the listing are kept rather than deleted: a
     * gateway dropping a model from `/models` temporarily is common, and
     * removing it would break the session pointing at it.
     */
    suspend fun mergeModels(endpointId: String, discovered: List<ModelSpec>) = update { settings ->
        val endpoints = settings.endpoints.map { endpoint ->
            if (endpoint.id != endpointId) return@map endpoint
            val byId = endpoint.models.associateBy { it.id }
            val merged = discovered.map { found ->
                val existing = byId[found.id]
                // How a model reasons is the endpoint's fact, and a stale one
                // is a 400: taken from the listing whenever it says, even over
                // hand-edited numbers. A listing that says nothing leaves
                // what a per-model lookup found.
                val saysHowItReasons = found.reasoningEfforts.isNotEmpty() || found.thinkingTypes.isNotEmpty()
                when {
                    existing == null -> found
                    existing.userEdited -> if (saysHowItReasons) existing.withLevelsOf(found) else existing
                    found.metadataFromEndpoint -> found.withinLearned(existing.learnedContextWindow)
                    else -> if (saysHowItReasons) existing.withLevelsOf(found) else existing
                }
                    // Whether it caches is the listing's to say, every time:
                    // a cache point a model stopped taking would be a 400.
                    .copy(caching = found.caching)
            }
            val removedButKept = endpoint.models.filter { kept -> discovered.none { it.id == kept.id } }
            val models = (merged + removedButKept).distinctBy { it.id }
            endpoint.copy(
                models = models,
                // An endpoint whose models were never chosen gets a default, so
                // the first message works without anyone typing a model id. A
                // default the user (or an earlier fetch) set is kept while it
                // still exists.
                // An image or speech model picked as default by an earlier
                // version, before its name was recognised, is replaced.
                defaultModelId = endpoint.defaultModelId
                    ?.takeIf { id -> models.any { it.id == id } }
                    ?.takeIf { id -> !ModelDiscovery.looksNonChat(id) || discovered.all { ModelDiscovery.looksNonChat(it.id) } }
                    ?: ModelDiscovery.pickDefault(discovered)?.id
                    ?: models.firstOrNull()?.id,
            )
        }
        settings.copy(endpoints = endpoints)
    }

    /**
     * Fill in one model from its endpoint's per-model route.
     *
     * Only when the detail actually reports a context window, and never over a
     * spec the user edited by hand. Capability flags combine conservatively:
     * tools stay off if either source said off, images on if either said on.
     */
    suspend fun applyModelDetail(endpointId: String, modelId: String, detail: ModelSpec) = update { settings ->
        val hasWindow = detail.metadataFromEndpoint
        val hasLevels = detail.reasoningEfforts.isNotEmpty()
        if (!hasWindow && !hasLevels) return@update settings
        settings.copy(
            endpoints = settings.endpoints.map { endpoint ->
                if (endpoint.id != endpointId) return@map endpoint
                endpoint.copy(
                    models = endpoint.models.map { model ->
                        when {
                            model.id != modelId -> model
                            // A hand-edited spec keeps its numbers, but levels
                            // are the endpoint's facts, not a preference.
                            model.userEdited || !hasWindow -> if (hasLevels) model.withLevelsOf(detail) else model
                            else -> model.copy(
                                displayName = detail.displayName
                                    .takeIf { it.isNotBlank() && it != detail.id }
                                    ?: model.displayName,
                                contextWindow = model.learnedContextWindow
                                    ?.coerceAtMost(detail.contextWindow)
                                    ?: detail.contextWindow,
                                maxOutputTokens = detail.maxOutputTokens,
                                supportsTools = model.supportsTools && detail.supportsTools,
                                supportsImages = model.supportsImages || detail.supportsImages,
                                reasoning = if (detail.reasoning != ReasoningSupport.NONE) detail.reasoning else model.reasoning,
                                thinkingTypes = detail.thinkingTypes.ifEmpty { model.thinkingTypes },
                                metadataFromEndpoint = true,
                                notes = detail.notes.ifBlank { model.notes },
                            ).let { if (hasLevels) it.withLevelsOf(detail) else it }
                        }
                    },
                )
            },
        )
    }

    /** A listed window, no larger than one the endpoint has shown it does not take. */
    private fun ModelSpec.withinLearned(learned: Int?): ModelSpec =
        if (learned == null || learned >= contextWindow) {
            copy(learnedContextWindow = learned)
        } else {
            copy(contextWindow = learned, learnedContextWindow = learned)
        }

    private fun ModelSpec.withLevelsOf(detail: ModelSpec) = copy(
        reasoning = if (detail.reasoning != ReasoningSupport.NONE) detail.reasoning else reasoning,
        reasoningEfforts = detail.reasoningEfforts,
        defaultReasoningEffort = detail.defaultReasoningEffort,
        reasoningMandatory = detail.reasoningMandatory,
        thinkingTypes = detail.thinkingTypes.ifEmpty { thinkingTypes },
    )

    /**
     * Persist a context limit learned from a provider's overflow error.
     *
     * This is the self-correcting path that replaces a hardcoded table of model
     * windows: the first time a prompt is too long, the provider states the
     * real maximum, and it is recorded here so it is not hit twice.
     */
    suspend fun recordDiscoveredContextLimit(
        endpointId: String,
        modelId: String,
        limit: Int,
    ) = update { settings ->
        settings.copy(
            endpoints = settings.endpoints.map { endpoint ->
                if (endpoint.id != endpointId) return@map endpoint
                endpoint.copy(
                    models = endpoint.models.map { model ->
                        if (model.id != modelId || model.contextWindow <= limit) {
                            model
                        } else {
                            model.copy(
                                contextWindow = limit,
                                learnedContextWindow = limit,
                                metadataFromEndpoint = true,
                                notes = "context window ${limit} learned from the endpoint's " +
                                    "overflow error",
                            )
                        }
                    },
                )
            },
        )
    }

    suspend fun recordTestResult(endpointId: String, result: String) = update { settings ->
        settings.copy(
            endpoints = settings.endpoints.map {
                if (it.id == endpointId) {
                    it.copy(lastTestedAt = System.currentTimeMillis(), lastTestResult = result)
                } else {
                    it
                }
            },
        )
    }

    /* --------------------------------------------------------------- agents */

    suspend fun upsertAgent(agent: AgentDefinition) = update { settings ->
        val agents = settings.agents.toMutableList()
        val index = agents.indexOfFirst { it.id == agent.id }
        if (index >= 0) agents[index] = agent else agents += agent
        settings.copy(agents = agents, activeAgentId = settings.activeAgentId ?: agent.id)
    }

    suspend fun removeAgent(agentId: String) = update { settings ->
        if (settings.agents.firstOrNull { it.id == agentId }?.builtIn == true) {
            settings // built-ins are not removable; they are the fallback
        } else {
            val agents = settings.agents.filterNot { it.id == agentId }
            settings.copy(
                agents = agents,
                activeAgentId = settings.activeAgentId?.takeIf { it != agentId }
                    ?: agents.firstOrNull()?.id,
            )
        }
    }

    suspend fun setActiveAgent(agentId: String) = update { it.copy(activeAgentId = agentId) }

    /** Tick or untick one of Settings › Rules: whether From Rules lets [rule] through without asking. */
    suspend fun setApprovalRule(rule: dev.apkcli.core.model.ApprovalRule, allowed: Boolean) = update { settings ->
        settings.copy(approvalRules = if (allowed) settings.approvalRules + rule else settings.approvalRules - rule)
    }

    suspend fun setThinkingMode(mode: ThinkingMode) = update { it.copy(thinkingMode = mode) }

    /**
     * Remember [level] for one model; null forgets the pick, so the model's
     * own default applies again. Only levels the model published reach here.
     */
    suspend fun setReasoningEffort(endpointId: String, modelId: String, level: String?) = update { settings ->
        val key = ReasoningLevels.key(endpointId, modelId)
        settings.copy(
            reasoningEfforts = if (level == null) settings.reasoningEfforts - key else settings.reasoningEfforts + (key to level),
        )
    }

    /* --------------------------------------------------------------- search */

    suspend fun setSearch(search: SearchSettings) = update { it.copy(search = search) }

    companion object {
        private const val TAG = "SettingsRepository"
        private val KEY_SETTINGS = stringPreferencesKey("settings_json")

        fun newId(prefix: String): String =
            "$prefix-" + UUID.randomUUID().toString().replace("-", "").take(12)

        /**
         * Shipped configuration: the built-in agents and no connection.
         *
         * Every service is one key away through its scheme, and a custom
         * endpoint is first-class. Until one is added, Home asks for a model
         * and a message says where to add one. No model list is baked in;
         * models are fetched from the endpoint.
         */
        fun defaults() = AppSettings().withBuiltInAgents()
    }
}

/**
 * Remove the OpenRouter connection settings before version 4 created on
 * first run, if it never got a key. [hasKey] asks the [SecretStore], where
 * keys live instead of here.
 *
 * Nothing could be sent through it without one, yet as the only connection
 * it was the active one, and the list said so. Its model list says nothing
 * about use — OpenRouter lists models without a key, and sending a message
 * lists them — so the key is the one sign it was set up. With one it stays.
 */
internal fun AppSettings.withoutUnusedFirstRun(hasKey: (endpointId: String) -> Boolean): AppSettings {
    if (schemaVersion >= 4) return this
    val unused = endpoints.firstOrNull { it.id == EndpointDefaults.RETIRED_FIRST_RUN_ID } ?: return this
    if (hasKey(unused.id)) return this
    val kept = endpoints - unused
    val wasActive = activeEndpoint?.id == unused.id
    return copy(
        endpoints = kept,
        activeEndpointId = activeEndpointId?.takeIf { id -> kept.any { it.id == id } } ?: kept.firstOrNull()?.id,
        // A model of the connection that went is no one's choice any more.
        activeModelId = activeModelId.takeUnless { wasActive },
    )
}

/**
 * Give every endpoint its scheme, and a known service's endpoint that
 * service's current facts.
 *
 * Runs first, while the version is still the one the blob was written
 * with: the AnyModel clean-up has to see it. AnyModel was seeded by earlier
 * builds; one that never got a model list was never given a key, and goes.
 * One that was used stays, as a custom endpoint.
 */
internal fun AppSettings.withSchemes(): AppSettings {
    val kept = if (schemaVersion < 3) {
        endpoints.filterNot { it.id == EndpointDefaults.RETIRED_ANYMODEL_ID && it.models.isEmpty() }
    } else {
        endpoints
    }
    val schemed = kept.map { endpoint -> ConnectionScheme.of(endpoint).applyTo(endpoint) }
    if (schemed == endpoints) return this
    return copy(
        endpoints = schemed,
        activeEndpointId = activeEndpointId?.takeIf { id -> schemed.any { it.id == id } }
            ?: schemed.firstOrNull()?.id,
    )
}

/** The built-in agents' ids. The three modes, and the Explorer subagents investigate with. */
object BuiltInAgents {
    const val CONFIRM = "builtin-confirm"
    const val RULES = "builtin-rules"
    const val BYPASS = "builtin-bypass"
    const val EXPLORER = "builtin-explorer"

    /**
     * Agents earlier builds offered, and the mode each became: the Engineer
     * asked before every change, the Runner never did, and the read-only
     * Explorer is no longer a mode, so a chat that used it asks about
     * everything.
     */
    val RETIRED: Map<String, String> = mapOf(
        "builtin-engineer" to CONFIRM,
        "builtin-runner" to BYPASS,
        EXPLORER to CONFIRM,
    )
}

/**
 * The built-in agents: the three modes the user picks from, which differ in
 * when they ask, and the Explorer, which is not offered as a mode and exists
 * for subagents dispatched to investigate.
 *
 * Settings saved by a build that had the Engineer, Explorer and Runner
 * agents are carried over: the active one becomes the mode it amounts to
 * ([BuiltInAgents.RETIRED]), with the model it was set to.
 */
internal fun AppSettings.withBuiltInAgents(): AppSettings {
    val builtIns = listOf(
        AgentDefinition(
            id = BuiltInAgents.CONFIRM,
            name = "Confirm",
            description = "Asks before every change: writing or deleting files, commands, packages. Reads and searches on its own.",
            instructions = "",
            thinkingMode = ThinkingMode.AUTO,
            autonomy = AgentDefinition.Autonomy.SUPERVISED,
            canDispatchSubagents = true,
            builtIn = true,
        ),
        AgentDefinition(
            id = BuiltInAgents.RULES,
            name = "From Rules",
            description = "Asks only about what the rules in Settings do not let through.",
            instructions = "",
            thinkingMode = ThinkingMode.AUTO,
            autonomy = AgentDefinition.Autonomy.RULES,
            autoApproved = approvalRules,
            canDispatchSubagents = true,
            builtIn = true,
        ),
        AgentDefinition(
            id = BuiltInAgents.BYPASS,
            name = "Bypass Mode",
            description = "Asks nothing and works the task to the end.",
            instructions = """
                Work the task to completion without asking for confirmation between
                steps. Every edit you make is recorded and revertible, so bias
                towards acting; a deleted directory or binary file is not, so delete
                only what the task needs gone.

                Verify before you report done: run the build, run the tests, re-read
                what you wrote. A command that exited zero is not proof the feature
                works. If you cannot verify something, say so explicitly instead of
                implying success.
            """.trimIndent(),
            thinkingMode = ThinkingMode.AUTO,
            autonomy = AgentDefinition.Autonomy.AUTOPILOT,
            canDispatchSubagents = true,
            builtIn = true,
        ),
        AgentDefinition(
            id = BuiltInAgents.EXPLORER,
            name = "Explorer",
            description = "Read-only. Investigates and explains without touching anything.",
            instructions = """
                You investigate and report. You do not modify files, run commands that
                change state, or make commits — those tools are unavailable to you.

                Answer with specifics: file paths, line numbers, the actual call chain.
                A conclusion without a citation to the code is not useful. When you are
                uncertain, say what you checked and what you could not determine.
            """.trimIndent(),
            // Taken from the tool catalogue rather than restated here. The
            // duplicate list this replaces had already drifted — it was missing
            // fs_stat and web_fetch, so the read-only agent silently could not
            // check whether a path existed.
            allowedTools = Tools.readOnlyToolNames,
            thinkingMode = ThinkingMode.DEEP,
            autonomy = AgentDefinition.Autonomy.READ_ONLY,
            canDispatchSubagents = false,
            builtIn = true,
            hidden = true,
        ),
    )

    val merged = builtIns.map { builtIn ->
        // The user's model choice for a built-in carries over, from the agent
        // itself or from the retired one it replaces. What makes it the mode
        // it is — autonomy, rules, instructions — is the shipped one's.
        val existing = agents.firstOrNull { it.id == builtIn.id }
            ?: agents.firstOrNull { BuiltInAgents.RETIRED[it.id] == builtIn.id && it.id != BuiltInAgents.EXPLORER }
        if (existing == null) {
            builtIn
        } else {
            builtIn.copy(endpointId = existing.endpointId, modelId = existing.modelId)
        }
    }
    val retired = BuiltInAgents.RETIRED.keys - BuiltInAgents.EXPLORER
    val custom = agents.filterNot { candidate -> builtIns.any { it.id == candidate.id } || candidate.id in retired }

    val wanted = activeAgentId?.let { BuiltInAgents.RETIRED[it] ?: it }
    val active = wanted?.takeIf { id -> (merged + custom).any { it.id == id && !it.hidden } } ?: BuiltInAgents.CONFIRM

    return copy(agents = merged + custom, activeAgentId = active)
}
