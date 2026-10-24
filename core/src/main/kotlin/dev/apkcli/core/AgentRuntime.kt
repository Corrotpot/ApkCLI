package dev.apkcli.core

import android.content.Context
import android.util.Log
import dev.apkcli.core.agent.AgentEvent
import dev.apkcli.core.agent.AgentLoop
import dev.apkcli.core.agent.ApprovalGate
import dev.apkcli.core.agent.Compactor
import dev.apkcli.core.agent.ContextBudget
import dev.apkcli.core.agent.SubagentOutcome
import dev.apkcli.core.agent.SubagentRequest
import dev.apkcli.core.agent.SubagentTracker
import dev.apkcli.core.agent.ToolRegistry
import dev.apkcli.core.agent.Tools
import dev.apkcli.core.agent.UiBridge
import dev.apkcli.core.agent.tools.SearchBackend
import dev.apkcli.core.llm.LlmClient
import dev.apkcli.core.llm.LlmError
import dev.apkcli.core.model.AgentDefinition
import dev.apkcli.core.model.AppSettings
import dev.apkcli.core.model.Block
import dev.apkcli.core.model.ConversationDigest
import dev.apkcli.core.model.Endpoint
import dev.apkcli.core.model.ModelSpec
import dev.apkcli.core.model.ReasoningSupport
import dev.apkcli.core.model.Role
import dev.apkcli.core.model.SearchMode
import dev.apkcli.core.model.SearchSettings
import dev.apkcli.core.nativebridge.NativeCore
import dev.apkcli.core.pkg.DevicePackages
import dev.apkcli.core.pkg.PackageManager
import dev.apkcli.core.runtime.Bootstrap
import dev.apkcli.core.runtime.EnvLayout
import dev.apkcli.core.runtime.ExecPolicy
import dev.apkcli.core.runtime.PtyManager
import dev.apkcli.core.runtime.ShellRunner
import dev.apkcli.core.runtime.TerminalSessions
import dev.apkcli.core.runtime.TranscriptInput
import dev.apkcli.core.sched.LoadGovernor
import dev.apkcli.core.store.BuiltInAgents
import dev.apkcli.core.store.ChangeLedger
import dev.apkcli.core.store.ConversationStore
import dev.apkcli.core.store.SecretStore
import dev.apkcli.core.store.SettingsRepository
import dev.apkcli.core.web.WebFetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Wires the core together.
 *
 * Hand-written rather than Hilt or Koin: the graph is a dozen singletons with
 * obvious dependencies, and an annotation processor would add a build step, a
 * KSP version to keep aligned with Kotlin, and a layer of indirection over
 * something that reads fine as straight-line code.
 *
 * Created once per process. The `:engine`, `:tools` and `:shell` processes each
 * build their own — they are separate address spaces, and that is the point:
 * the agent loop and the native tool work cannot stall the UI process's main
 * thread no matter what they do.
 */
class AgentRuntime(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    /* --------------------------------------------------------- environment */

    val layout = EnvLayout(context)
    val policy = ExecPolicy(context, layout)
    val governor = LoadGovernor(context, scope)
    val shell = ShellRunner(layout, policy, governor)

    /**
     * Null when the native core failed to load — PTY work needs it. Everything
     * that can degrade does; this one cannot, so callers check.
     *
     * Lazy, and that matters: reading it calls `NativeCore.available`, which
     * triggers `System.loadLibrary`. This object is constructed in
     * `Application.onCreate`, so an eager initialiser would map a multi-megabyte
     * shared object and run its init on the main thread before the first frame.
     * Deferring it means the load happens on whichever background dispatcher
     * first needs a shell — or in `start()`, which already runs off the main
     * thread.
     */
    val ptyManager: PtyManager? by lazy {
        if (NativeCore.available) PtyManager(layout, policy) else null
    }

    /**
     * Every terminal in the process: the user's and the agent's.
     *
     * Shared between the terminal panel and the agent's background tools on
     * purpose — see [TerminalSessions].
     */
    val terminals: TerminalSessions? by lazy {
        ptyManager?.let { TerminalSessions(it, scope) }
    }

    /** Lines the user types into the agent's transcript; see [TranscriptInput]. */
    val transcriptInput: TranscriptInput? by lazy {
        terminals?.let { TranscriptInput(shell, it, layout, scope) }
    }

    val webFetcher by lazy { WebFetcher(context) }

    /**
     * Termux packages, where an installed binary can run at all: the power
     * build (targetSdk 28), on a CPU Termux builds for. Null otherwise, and the
     * `pkg_*` tools are then not registered.
     */
    val packages: PackageManager? by lazy {
        if (policy.dataDirExecAllowed) DevicePackages.create(context, layout) else null
    }

    /* ------------------------------------------------------------- storage */

    val secrets = SecretStore(context)
    val settingsRepository = SettingsRepository(context, secrets::hasApiKey)
    val store = ConversationStore(layout, governor)

    /** Per-session file changes, for the "N files changed" bar and its revert. */
    val changes = ChangeLedger(store, layout)

    /* ----------------------------------------------------------------- llm */

    val llm = LlmClient(secrets)

    /* --------------------------------------------------------------- agent */

    @Volatile
    private var registryCache: ToolRegistry? = null

    @Volatile
    private var registryKey: String = ""

    /**
     * The tool catalogue for these settings.
     *
     * Cached, because building forty tools and estimating their schema tokens
     * runs on every turn otherwise. The key covers everything that changes the
     * *set* of tools — a stale cache here would mean a configured search
     * endpoint never appearing, or `pkg_install` surviving a flavour change.
     */
    fun registry(settings: AppSettings): ToolRegistry {
        val search = settings.search
        val key = "${search.mode}|${search.url}|${search.authHeaderName}|${search.region}|" +
            "${packages != null}|${settings.remoteNode?.enabled}"

        registryCache?.let { if (key == registryKey) return it }

        val backend = when {
            !search.configured -> null
            search.mode == SearchMode.KEYLESS -> SearchBackend.DuckDuckGo(region = search.region)
            else -> SearchBackend.JsonApi(
                url = search.url,
                apiKey = secrets.apiKey(SearchSettings.CREDENTIAL_ID),
                authHeaderName = search.authHeaderName,
            )
        }

        val built = Tools.registry(
            Tools.Options(
                search = backend,
                packageManagementAvailable = packages != null,
            ),
        )
        registryCache = built
        registryKey = key
        return built
    }

    /** Drop the cached registry, after settings that affect the tool set change. */
    fun invalidateRegistry() {
        registryCache = null
        registryKey = ""
    }

    private fun budget(settings: AppSettings) = ContextBudget(settings.context)

    private fun compactor(settings: AppSettings) = Compactor(store, llm, settings.context)

    private fun loop(settings: AppSettings) = AgentLoop(
        store = store,
        llm = llm,
        registry = registry(settings),
        compactor = compactor(settings),
        budget = budget(settings),
        layout = layout,
        policy = policy,
        shell = shell,
        governor = governor,
        terminals = terminals,
        webFetcher = webFetcher,
        packages = packages,
    )

    /* ------------------------------------------------------------ lifecycle */

    data class StartupReport(
        val nativeCoreLoaded: Boolean,
        val nativeCoreError: String?,
        val bootstrap: Bootstrap.Report,
        val storeOpened: Boolean,
        val storeError: String?,
    ) {
        /** Can the app do anything useful? */
        val usable: Boolean get() = storeOpened

        fun describe(): String = buildString {
            appendLine("native core: ${if (nativeCoreLoaded) "loaded" else "absent — $nativeCoreError"}")
            appendLine("exec tier: ${bootstrap.tier.name.lowercase()}, probe ${if (bootstrap.execWorks) "ok" else "FAILED"}")
            appendLine("commands linked: ${bootstrap.linkedCommands.size}, busybox applets: ${bootstrap.appletCount}")
            if (bootstrap.warnings.isNotEmpty()) {
                appendLine("warnings:")
                bootstrap.warnings.forEach { appendLine("  - $it") }
            }
            if (!storeOpened) appendLine("store: FAILED — $storeError")
        }
    }

    /**
     * One-time startup. Safe to call again; the bootstrap is idempotent.
     *
     * Deliberately returns a report instead of throwing. A device where exec is
     * blocked, or where the native library is missing, should still open and
     * explain itself — an IDE that shows a stack trace on launch and nothing
     * else is useless for diagnosing exactly this.
     */
    suspend fun start(forceBootstrap: Boolean = false): StartupReport {
        NativeCore.preload()
        governor.start()

        val bootstrapReport = Bootstrap(context, layout, policy).run(forceBootstrap)
        if (!bootstrapReport.execWorks) {
            Log.w(TAG, "exec probe failed: ${bootstrapReport.execDetail}")
        }

        // Installed packages reach their files through the prefix alias in the
        // data directory; put it back if something removed it.
        runCatching { packages?.ensureRelocation() }
            .onSuccess { choice -> choice?.note?.let { Log.w(TAG, "package relocation: $it") } }
            .onFailure { Log.w(TAG, "could not check the package prefix alias: ${it.message}") }

        var storeOpened = false
        var storeError: String? = null
        runCatching { store.stats("__probe__") }
            .onSuccess { storeOpened = true }
            .onFailure { error ->
                // A missing session is fine — that means the store opened and
                // answered. Anything else is a real failure.
                if (error.message?.contains("not_found", ignoreCase = true) == true ||
                    error.message?.contains("no such", ignoreCase = true) == true
                ) {
                    storeOpened = true
                } else {
                    storeError = error.message
                    Log.e(TAG, "conversation store unavailable", error)
                }
            }

        if (storeOpened) {
            // A turn interrupted by process death leaves its assistant message
            // marked `streaming`, which the UI renders as a permanent spinner.
            runCatching { store.repairInterruptedMessages() }
                .onFailure { Log.w(TAG, "could not repair interrupted messages: ${it.message}") }

            // Reclaim blob storage. Before/after snapshots for every edit, plus
            // spilled tool output, are what actually grows the database, and
            // nothing needs them once their session is done.
            runCatching { store.pruneBlobs() }
                .onFailure { Log.w(TAG, "blob prune failed: ${it.message}") }
        }

        return StartupReport(
            nativeCoreLoaded = NativeCore.available,
            nativeCoreError = NativeCore.failure?.message,
            bootstrap = bootstrapReport,
            storeOpened = storeOpened,
            storeError = storeError,
        )
    }

    fun shutdown() {
        // Touching the lazy properties here would load the native library just
        // to tear it down, so only release what was actually started.
        if (NativeCore.available) {
            runCatching { terminals?.closeAll() }
            runCatching { ptyManager?.closeAll() }
        }
        governor.shutdown()
        store.close()
    }

    /* ----------------------------------------------------------- execution */

    /**
     * Run a turn.
     *
     * @param workspace the project directory; must be inside the sandbox.
     * @param userLanguage the user's language setting as an English name
     *   ("Russian"), used when a message does not make its own language clear.
     */
    fun runTurn(
        sessionId: String,
        workspace: File,
        settings: AppSettings,
        agent: AgentDefinition,
        userText: String?,
        attachments: List<Block.Image> = emptyList(),
        ui: UiBridge?,
        approvals: ApprovalGate,
        userLanguage: String? = null,
        liveAgent: (() -> AgentDefinition?)? = null,
    ): Flow<AgentEvent> = flow {
        val ready = when (val resolution = resolveModel(settings)) {
            is ModelResolution.Ready -> resolution
            ModelResolution.NoEndpoint -> {
                emitSetupFailure(
                    "No connection is configured. Add one in Settings › Connections — " +
                        "Anthropic, OpenAI, OpenRouter and Kiro only need a key.",
                )
                return@flow
            }
            is ModelResolution.NoModels -> {
                emitSetupFailure(
                    resolution.listingError?.let { "Could not list the models of ${resolution.endpoint.name}: $it" }
                        ?: "${resolution.endpoint.name} does not list its models. Add a model id " +
                            "in Settings › Endpoints.",
                )
                return@flow
            }
        }
        val turnSettings = ready.settings

        val config = AgentLoop.TurnConfig(
            sessionId = sessionId,
            workspace = workspace,
            agent = agent,
            endpoint = ready.endpoint,
            model = ready.model,
            settings = turnSettings,
            approvals = approvals,
            ui = ui,
            dispatchSubagent = if (agent.canDispatchSubagents) {
                { request ->
                    dispatchSubagent(request, workspace, turnSettings, approvals, sessionId, parentAgent = { liveAgent?.invoke() ?: agent })
                }
            } else {
                null
            },
            userLanguage = userLanguage,
            liveAgent = liveAgent,
        )

        loop(turnSettings).run(config, userText, attachments).collect { emit(it) }
    }
        // The producer side — prompt assembly, token estimation over a window
        // that can be a million tokens, store writes, compaction — runs off the
        // main thread. Only the collector (the ViewModel applying events to UI
        // state) stays where the caller put it. Events are still emitted from
        // the flow's own coroutine, so flowOn is legal here.
        .flowOn(Dispatchers.Default)

    /**
     * /compact: fold the conversation since the last summary into a new one,
     * keeping [focus] in detail. Called when no turn runs in the session.
     *
     * Not the loop's compaction, which runs before a request once the window
     * is nearly full and keeps the latest turns: this archives every live
     * message, spends no request on a conversation too short to be worth a
     * summary, keeps no summary that gives back too little, and on a failure
     * archives nothing — no request is waiting that has to fit.
     *
     * Events: [AgentEvent.CompactionStarted], then [AgentEvent.CompactionFinished],
     * or [AgentEvent.CompactionSkipped] or [AgentEvent.CompactionFailed] in
     * its place.
     */
    fun compactNow(sessionId: String, settings: AppSettings, focus: String?): Flow<AgentEvent> = flow {
        val ready = when (val resolution = resolveModel(settings)) {
            is ModelResolution.Ready -> resolution
            ModelResolution.NoEndpoint -> {
                emit(AgentEvent.CompactionFailed(AgentEvent.CompactionFailed.Kind.NO_MODEL))
                return@flow
            }
            is ModelResolution.NoModels -> {
                emit(AgentEvent.CompactionFailed(AgentEvent.CompactionFailed.Kind.NO_MODEL, resolution.listingError))
                return@flow
            }
        }
        val live = store.window(sessionId)
        val conversation = live.filter { it.seq >= 0 && !it.pinned }
        val tooLittle = when {
            conversation.isEmpty() -> AgentEvent.CompactionSkipped.Why.NOTHING_NEW
            conversation.sumOf { it.tokens } < Compactor.MIN_MANUAL_TOKENS -> AgentEvent.CompactionSkipped.Why.TOO_SHORT
            else -> null
        }
        if (tooLittle != null) {
            emit(AgentEvent.CompactionSkipped(tooLittle, manual = true))
            return@flow
        }
        val plan = budget(ready.settings).planWhole(live)
        emit(AgentEvent.CompactionStarted(plan.reason, manual = true))
        val result = compactor(ready.settings).compact(
            sessionId = sessionId,
            messages = live,
            plan = plan,
            endpoint = ready.endpoint,
            model = ready.model,
            previous = store.latestDigest(sessionId),
            focus = focus,
            trigger = ConversationDigest.TRIGGER_COMMAND,
            minGain = Compactor.MIN_MANUAL_GAIN,
        )
        when (result) {
            is Compactor.Result.Compacted ->
                emit(AgentEvent.CompactionFinished(result.digest, result.archivedMessages, result.tokensFreed))
            is Compactor.Result.Skipped -> emit(
                AgentEvent.CompactionSkipped(
                    if (result.reason == Compactor.NOT_SHORTER) {
                        AgentEvent.CompactionSkipped.Why.NOT_SHORTER
                    } else {
                        AgentEvent.CompactionSkipped.Why.NOTHING_NEW
                    },
                    manual = true,
                ),
            )
            is Compactor.Result.Failed ->
                emit(AgentEvent.CompactionFailed(AgentEvent.CompactionFailed.Kind.NO_SUMMARY, result.detail))
        }
    }.flowOn(Dispatchers.Default)

    private suspend fun FlowCollector<AgentEvent>.emitSetupFailure(message: String) {
        emit(AgentEvent.Failed(LlmError(LlmError.Kind.CONFIGURATION, message), recoverable = false))
        emit(AgentEvent.TurnFinished(0, AgentEvent.StopReason.ERROR))
    }

    /* -------------------------------------------------------------- models */

    /** The endpoint and model a turn will use, or why there are none. */
    sealed interface ModelResolution {
        data class Ready(val settings: AppSettings, val endpoint: Endpoint, val model: ModelSpec) : ModelResolution

        data object NoEndpoint : ModelResolution

        /** [listingError] is null when the endpoint lists no models rather than failing to. */
        data class NoModels(val endpoint: Endpoint, val listingError: String?) : ModelResolution
    }

    /** Endpoint/model pairs whose own route was already asked in this process. */
    private val describedModels: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Fetch an endpoint's models and store them, choosing a default when it
     * has none. Returns what was listed; empty when the endpoint lists nothing.
     *
     * @throws java.io.IOException when the listing failed, with the
     *   provider's reason.
     */
    suspend fun refreshModels(endpointId: String): List<ModelSpec> {
        // Saving a key starts a fetch from Settings and another from the chat,
        // which watches for keys too. Callers that overlap share one request.
        // Run in the app scope, so a caller leaving does not abort the others.
        val listing = inflightListings.computeIfAbsent(endpointId) {
            scope.async(start = CoroutineStart.LAZY) { listAndStore(endpointId) }.also { deferred ->
                deferred.invokeOnCompletion { inflightListings.remove(endpointId, deferred) }
            }
        }
        return listing.await()
    }

    private val inflightListings = ConcurrentHashMap<String, Deferred<List<ModelSpec>>>()

    private suspend fun listAndStore(endpointId: String): List<ModelSpec> {
        val endpoint = settingsRepository.current().endpoints.firstOrNull { it.id == endpointId }
            ?: return emptyList()
        val discovered = llm.listModels(endpoint)
        if (discovered.isNotEmpty()) settingsRepository.mergeModels(endpointId, discovered)
        return discovered
    }

    /**
     * Ask for one model's context window on its own route, when the listing
     * left it out — AnyModel publishes windows only per model.
     *
     * Once per model per process, so a route that has nothing to say costs one
     * request rather than one per turn. Returns the stored spec afterwards.
     */
    suspend fun describeIfUnverified(endpointId: String, modelId: String): ModelSpec? {
        val endpoint = settingsRepository.current().endpoints.firstOrNull { it.id == endpointId } ?: return null
        val model = endpoint.modelById(modelId) ?: return null
        if (!needsDescribing(model)) return model
        if (!describedModels.add("${endpoint.id}|${endpoint.baseUrl}|${model.id}")) return model
        val detail = llm.describeModel(endpoint, model.id) ?: return model
        settingsRepository.applyModelDetail(endpointId, modelId, detail)
        return settingsRepository.current().endpoints.firstOrNull { it.id == endpointId }?.modelById(modelId)
            ?: model
    }

    /**
     * Whether the model's own route may know more than the listing said: the
     * context window, or — on gateways like AnyModel that publish them only
     * per model — its reasoning levels. OpenRouter's listing already carries
     * both, so its models are never looked up one by one.
     */
    private fun needsDescribing(model: ModelSpec): Boolean = !model.userEdited && (
        !model.metadataFromEndpoint ||
            (model.reasoningEfforts.isEmpty() && model.reasoning != ReasoningSupport.OPENROUTER_UNIFIED)
        )

    /**
     * Decide the endpoint and model for a turn.
     *
     * This is what makes a model id optional. An endpoint with no models yet
     * has them listed and stored here, and the default is used — so a freshly
     * added endpoint answers its first message without anyone typing an id. A
     * model whose window the listing did not report gets one lookup on its own
     * route before it is budgeted against a guess.
     */
    suspend fun resolveModel(settings: AppSettings): ModelResolution {
        val endpoint = settings.activeEndpoint ?: return ModelResolution.NoEndpoint
        var current = settings
        var model = settings.activeModel

        if (model == null) {
            val listingError = try {
                refreshModels(endpoint.id)
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.w(TAG, "listing models of ${endpoint.name} failed: ${t.message}")
                t.message ?: t.javaClass.simpleName
            }
            current = settingsRepository.current()
            model = current.activeModel
                ?: return ModelResolution.NoModels(current.activeEndpoint ?: endpoint, listingError)
        }

        if (needsDescribing(model)) {
            val activeId = current.activeEndpoint?.id ?: endpoint.id
            val described = describeIfUnverified(activeId, model.id)
            if (described != null && described != model) {
                current = settingsRepository.current()
                model = current.activeModel ?: described
            }
        }

        return ModelResolution.Ready(current, current.activeEndpoint ?: endpoint, model)
    }

    /**
     * Run a subagent and return its written answer.
     *
     * The subagent gets its **own session**, which is the whole point: its file
     * reads and tool output land in a separate conversation and never enter the
     * parent's context. Only the final text comes back.
     *
     * It gets no [UiBridge] (so it cannot touch the task list or show files)
     * and no dispatch function of its own (so subagents cannot nest
     * indefinitely). Calls that need approval go to [parentApprovals]: the
     * user's dialog in the conversation that dispatched it.
     *
     * Its file edits are recorded under [parentSessionId], though: they are
     * changes in the chat the user is looking at, listed there, and taken
     * back by a checkpoint there.
     *
     * Two agents to dispatch: the read-only Explorer, and a worker, which is
     * the parent's own mode ([parentAgent], read on every call) — so a
     * subagent's change asks exactly when the parent's would, and ticking a
     * rule or switching to Bypass Mode reaches it at once. A mode's id, or an
     * agent id of earlier builds, is a worker too; an agent of the user's own
     * runs as itself.
     */
    private suspend fun dispatchSubagent(
        request: SubagentRequest,
        workspace: File,
        settings: AppSettings,
        parentApprovals: ApprovalGate,
        parentSessionId: String,
        parentAgent: () -> AgentDefinition,
    ): SubagentOutcome {
        val requested = request.agentId.trim()
        val custom = settings.agents.firstOrNull { it.id == requested && !it.builtIn }
        val worker = custom == null && requested != BuiltInAgents.EXPLORER &&
            (requested == WORKER_ID || requested in BuiltInAgents.RETIRED || settings.agents.any { it.id == requested && it.builtIn })
        val workerOf = { parent: AgentDefinition -> parent.copy(name = "Worker", canDispatchSubagents = false) }
        val agent = when {
            custom != null -> custom
            worker -> workerOf(parentAgent())
            else -> settings.agents.firstOrNull { it.id == requested }
        } ?: return SubagentOutcome(
            "No agent '$requested'. Dispatch '${BuiltInAgents.EXPLORER}' to investigate without " +
                "changing anything, or '$WORKER_ID' to change files and run commands under the current approval mode.",
            run = null,
        )

        val endpoint = settings.activeEndpoint ?: return SubagentOutcome("No endpoint configured.", run = null)
        val model = settings.activeModel ?: endpoint.defaultModel
            ?: return SubagentOutcome("No model selected.", run = null)

        val session = store.createSession(
            title = "subagent: ${request.prompt.take(50)}",
            workspace = workspace.absolutePath,
            agentId = agent.id,
            endpointId = endpoint.id,
            model = model.id,
        )
        // Archived from the start, not when it ends: a session that is not
        // archived is a chat, and a running subagent's turned up in the
        // project's chat history, where it could be opened or deleted from
        // under the subagent; after a crash it was even the chat the app
        // reopened. Archived sessions are still kept, and still searchable.
        runCatching { store.updateSession(session.id, archived = true) }
            .onFailure { Log.w(TAG, "could not archive subagent session ${session.id}", it) }

        val iterationCap = request.maxIterations.coerceIn(1, 60)
        val readOnly = agent.autonomy == AgentDefinition.Autonomy.READ_ONLY
        val tracker = SubagentTracker(
            agentId = requested,
            agentName = agent.name,
            readOnly = readOnly,
            sessionId = session.id,
            maxIterations = iterationCap,
        )

        val prompt = buildString {
            append(request.prompt)
            if (request.contextFiles.isNotEmpty()) {
                appendLine()
                appendLine()
                appendLine("Relevant files to start from:")
                request.contextFiles.forEach { appendLine("- $it") }
            }
        }

        val config = AgentLoop.TurnConfig(
            sessionId = session.id,
            workspace = workspace,
            agent = agent.copy(maxIterations = iterationCap),
            endpoint = endpoint,
            model = model,
            settings = settings,
            // Nobody watches the subagent's own session, but the parent's is
            // on screen: what needs approval is asked there, under the
            // subagent's name, and its card shows the step waiting. This used
            // to deny it all, and since a supervised agent asked about every
            // call, a supervised subagent could not run a single tool.
            // Read-only agents never ask.
            approvals = if (readOnly) {
                ApprovalGate.AlwaysAllow
            } else {
                ApprovalGate.Labelled(
                    inner = parentApprovals,
                    label = agent.name,
                    onAsk = tracker::asking,
                    onAnswer = tracker::answered,
                )
            },
            ui = null,
            dispatchSubagent = null,
            changesSessionId = parentSessionId,
            // A worker asks as the parent's mode does now, not as it did at dispatch.
            liveAgent = if (worker) {
                { workerOf(parentAgent()).copy(maxIterations = iterationCap) }
            } else {
                null
            },
        )

        // Everything it writes goes to the model that dispatched it, as it
        // always has; the card separates what it said on the way from its
        // answer. Bounded like everything a tool returns: the result is
        // spilled past the tool budget by the loop, with a note, not cut here.
        val answer = StringBuilder()
        var failure: String? = null
        var stoppedBy = AgentEvent.StopReason.COMPLETED
        // Its last reply stopped at the output limit: the answer may end mid-sentence.
        var lastReplyCut = false

        // Its card follows it at most every PROGRESS_MS: a snapshot per token
        // would redraw the chat at the provider's rate.
        val post = request.onProgress
        try {
            coroutineScope {
                val ticker = post?.let {
                    launch {
                        while (isActive) {
                            tracker.takeIfChanged()?.let(post)
                            delay(SUBAGENT_PROGRESS_MS)
                        }
                    }
                }
                try {
                    loop(settings).run(config, prompt).collect { event ->
                        when (event) {
                            is AgentEvent.TextDelta -> answer.append(event.text)
                            is AgentEvent.Failed -> failure = event.error.message
                            is AgentEvent.TurnFinished -> stoppedBy = event.stoppedBy
                            is AgentEvent.MessageCompleted -> if (event.message.role == Role.ASSISTANT) {
                                lastReplyCut = event.message.notices.any { it.kind == Block.Notice.OUTPUT_LIMIT }
                            }
                            else -> Unit
                        }
                        tracker.on(event)
                    }
                } finally {
                    ticker?.cancel()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (broken: Exception) {
            // The loop catches what a request can throw; this is what it did
            // not expect. The card says it, and so does the result.
            Log.w(TAG, "subagent ${session.id} broke off", broken)
            val reason = broken.message ?: broken.javaClass.simpleName
            failure = reason
            tracker.failed(reason)
        }

        val run = tracker.snapshot()
        post?.invoke(run)
        val text = when {
            failure != null -> "The subagent failed: $failure"
            answer.isBlank() -> "The subagent produced no answer (stopped by $stoppedBy)."
            stoppedBy == AgentEvent.StopReason.ITERATION_LIMIT ->
                answer.toString() + "\n\n[The subagent hit its iteration limit; this answer " +
                    "may be incomplete.]"
            lastReplyCut ->
                answer.toString() + "\n\n[The subagent's last reply stopped at its output limit; " +
                    "this answer may be cut off.]"
            else -> answer.toString()
        }
        return SubagentOutcome(text, run)
    }

    companion object {
        private const val TAG = "AgentRuntime"

        /** The subagent that changes things, under the dispatching chat's mode; see [dispatchSubagent]. */
        const val WORKER_ID = "builtin-worker"

        /** How often a running subagent's card is brought up to date, at most. */
        private const val SUBAGENT_PROGRESS_MS = 250L
    }
}
