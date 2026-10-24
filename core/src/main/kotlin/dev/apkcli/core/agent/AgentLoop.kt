package dev.apkcli.core.agent

import android.os.Build
import android.util.Log
import dev.apkcli.core.llm.CompletionRequest
import dev.apkcli.core.llm.LlmClient
import dev.apkcli.core.llm.LlmError
import dev.apkcli.core.llm.StreamEvent
import dev.apkcli.core.model.AgentDefinition
import dev.apkcli.core.model.AppSettings
import dev.apkcli.core.model.Block
import dev.apkcli.core.model.ConversationDigest
import dev.apkcli.core.model.Endpoint
import dev.apkcli.core.model.Message
import dev.apkcli.core.model.MessageStatus
import dev.apkcli.core.model.ModelSpec
import dev.apkcli.core.model.ReasoningLevels
import dev.apkcli.core.model.Role
import dev.apkcli.core.model.ThinkingMode
import dev.apkcli.core.model.ToolCallRecord
import dev.apkcli.core.model.ToolCallStatus
import dev.apkcli.core.nativebridge.NativeCore
import dev.apkcli.core.pkg.PackageManager
import dev.apkcli.core.runtime.EnvLayout
import dev.apkcli.core.runtime.ExecEcho
import dev.apkcli.core.runtime.ExecPolicy
import dev.apkcli.core.runtime.ShellRunner
import dev.apkcli.core.runtime.TerminalSessions
import dev.apkcli.core.sched.LoadGovernor
import dev.apkcli.core.store.ConversationStore
import dev.apkcli.core.web.WebFetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The agent turn.
 *
 * Shape of one iteration:
 *
 * ```
 *   build prompt  ->  compact if needed  ->  stream completion
 *        ^                                        |
 *        |                                        v
 *   append results  <-  execute tool calls  <-  tool calls?
 *                                               |  no
 *                                               v
 *                                            finish
 * ```
 *
 * Things worth knowing about how this is put together:
 *
 *  * **Events are emitted as they happen, not collected and flushed.** The
 *    `FlowCollector` is threaded into the streaming function rather than having
 *    it return a list, because buffering deltas until the response completes
 *    would make streaming decorative — the UI would freeze for the length of
 *    the answer and then fill in at once.
 *
 *  * **The assistant message is persisted empty, then updated.** Streaming into
 *    memory and saving at the end loses the whole response to a crash, to the
 *    low-memory killer, or to the user backgrounding the app. On a phone all
 *    three are routine.
 *
 *  * **Tool results become their own message.** Both wire protocols require the
 *    assistant message that made the calls, followed by the results. Merging
 *    them into one message is a 400.
 *
 *  * **Compaction happens before a request, never during.** Mid-stream is too
 *    late; the request has already been accepted or rejected.
 *
 *  * **Results are emitted per group; running output goes by a side door.**
 *    Parallel tool calls run in `async`, and emitting into a flow from several
 *    coroutines at once is illegal. So tools post their latest output to a
 *    channel ([ToolContext.onOutput]) and this loop, in the flow's own
 *    coroutine, passes it on as [AgentEvent.ToolOutput] while the group runs.
 */
class AgentLoop(
    private val store: ConversationStore,
    private val llm: LlmClient,
    private val registry: ToolRegistry,
    private val compactor: Compactor,
    private val budget: ContextBudget,
    private val layout: EnvLayout,
    private val policy: ExecPolicy,
    private val shell: ShellRunner,
    private val governor: LoadGovernor,
    private val terminals: TerminalSessions?,
    private val webFetcher: WebFetcher?,
    private val packages: PackageManager? = null,
) {

    /** Everything that varies per turn. */
    data class TurnConfig(
        val sessionId: String,
        val workspace: File,
        val agent: AgentDefinition,
        val endpoint: Endpoint,
        val model: ModelSpec,
        val settings: AppSettings,
        val approvals: ApprovalGate,
        val ui: UiBridge?,
        /** Null for a subagent, which must not dispatch further subagents. */
        val dispatchSubagent: (suspend (SubagentRequest) -> SubagentOutcome)? = null,
        /** The user's language setting, as an English name; see [SystemPrompt.Inputs.userLanguage]. */
        val userLanguage: String? = null,
        /**
         * The agent the user has selected right now, when it can change during
         * the turn. Permissions are checked against it on every call, so
         * switching to Bypass Mode mid-turn, or ticking a rule, stops the
         * questions at once instead of after the turn; the prompt and tool
         * list stay those of [agent].
         */
        val liveAgent: (() -> AgentDefinition?)? = null,
        /**
         * The session whose change ledger this turn's file edits go into. A
         * subagent's go into the chat that dispatched it; null is [sessionId].
         */
        val changesSessionId: String? = null,
    ) {
        /** Whose permissions a tool call runs under: the live selection, or the turn's agent. */
        fun currentAgent(): AgentDefinition = liveAgent?.invoke() ?: agent

        /** The level picked for this model, or its default; null when it publishes none. */
        val reasoningEffort: String?
            get() = settings.reasoningEffortFor(endpoint.id, model)

        /**
         * The prompt's reasoning guidance: from the model's published level
         * when it has one, so "off" in the picker also stops the prompt asking
         * for deliberation; otherwise the agent's own setting.
         */
        val thinkingMode: ThinkingMode
            get() = ReasoningLevels.thinkingModeFor(reasoningEffort)
                ?: if (agent.thinkingMode == ThinkingMode.AUTO) {
                    settings.thinkingMode
                } else {
                    agent.thinkingMode
                }

        val maxIterations: Int get() = agent.maxIterations.coerceIn(1, 200)
    }

    /**
     * Run one turn: the user said something, work until there is nothing left
     * to do.
     *
     * @param userText null to continue without adding a user message — used
     *   when resuming after an approval or an interruption.
     */
    fun run(
        config: TurnConfig,
        userText: String?,
        attachments: List<Block.Image> = emptyList(),
    ): Flow<AgentEvent> = flow {
        var iterations = 0
        var stopReason = AgentEvent.StopReason.COMPLETED

        // Compaction is attempted at most once per turn. Without this, a window
        // that stays over the threshold after compacting — because what remains
        // is pinned, or is one tool result larger than the recent-turn budget —
        // sends the loop round again, and each round costs a summarising
        // request. One failed attempt is a diagnosis, sixty is a bill.
        var compactionAttempted = false

        try {
            if (userText != null) {
                val user = store.append(
                    Message(
                        id = ConversationStore.newId("m"),
                        sessionId = config.sessionId,
                        role = Role.USER,
                        content = buildList {
                            // An image may be sent with no words: then there is
                            // no text block, rather than an empty one that some
                            // providers reject.
                            if (userText.isNotBlank() || attachments.isEmpty()) add(Block.Text(userText))
                            addAll(attachments)
                        },
                    ),
                )
                emit(AgentEvent.UserMessage(user))

                // The first user message names the session. Done here rather
                // than with a model call: free, instant, and good enough.
                if (user.seq == 0L) {
                    val title = userText.ifBlank { attachments.firstNotNullOfOrNull { it.label } ?: "Image" }
                    store.updateSession(config.sessionId, title = deriveTitle(title))
                }
            }

            while (iterations < config.maxIterations) {
                iterations++

                // Recall only on the first iteration. It is keyed off what the
                // user just asked, so re-running it for each round of tool
                // calls repeats an FTS query and prepends the same block again
                // for an unchanged query.
                val recallQuery = if (iterations == 1) userText?.takeIf { it.isNotBlank() } else null

                val prepared = prepare(config, recallQuery)
                lastAccounting = prepared.accounting
                emit(AgentEvent.ContextUpdated(prepared.accounting))

                if (budget.isHopeless(prepared.accounting)) {
                    emit(
                        AgentEvent.Failed(
                            LlmError(
                                LlmError.Kind.CONTEXT_OVERFLOW,
                                budget.hopelessExplanation(prepared.accounting),
                            ),
                            recoverable = false,
                        ),
                    )
                    stopReason = AgentEvent.StopReason.ERROR
                    break
                }

                if (budget.needsCompaction(prepared.accounting) && !compactionAttempted) {
                    compactionAttempted = true
                    val plan = budget.planCompaction(prepared.messages, prepared.accounting)
                    if (budget.isWorthCompacting(plan, prepared.accounting)) {
                        runCompaction(config, prepared, plan)
                        // The window changed; rebuild everything below it. This
                        // consumes an iteration deliberately, which is also the
                        // backstop if anything above goes wrong.
                        continue
                    }
                    // Over the threshold with nothing worth archiving. Send
                    // anyway: the provider may accept it, and if it does not,
                    // the overflow error carries the real limit and teaches us
                    // the window. Failing here would be worse than trying.
                    // It was reported as a compaction starting, whose English
                    // reason the chat then showed as if one ran.
                    Log.i(TAG, "over budget but nothing worth archiving (${plan.reason}); sending as-is")
                    emit(AgentEvent.CompactionSkipped(AgentEvent.CompactionSkipped.Why.NOTHING_ARCHIVABLE, manual = false))
                }

                val outcome = streamAssistantTurn(this, config, prepared, iterations)

                if (outcome.error != null) {
                    // LlmError.discoveredContextLimit may carry the model's real
                    // window, parsed out of an overflow error. The caller
                    // persists it so the mistake is not repeated.
                    emit(AgentEvent.Failed(outcome.error, outcome.error.retryable))
                    stopReason = AgentEvent.StopReason.ERROR
                    break
                }

                if (outcome.toolUses.isEmpty()) {
                    stopReason = AgentEvent.StopReason.COMPLETED
                    break
                }

                executeToolCalls(this, config, outcome.assistantMessageId, outcome.toolUses, outcome.cutOff)

                if (iterations >= config.maxIterations) {
                    stopReason = AgentEvent.StopReason.ITERATION_LIMIT
                }
            }
        } catch (cancelled: CancellationException) {
            // Leave the partial message visible and marked rather than deleting
            // work the user watched appear.
            stopReason = AgentEvent.StopReason.CANCELLED
            throw cancelled
        }

        emit(AgentEvent.TurnFinished(iterations, stopReason))
    }

    private suspend fun FlowCollector<AgentEvent>.runCompaction(
        config: TurnConfig,
        prepared: Prepared,
        plan: ContextBudget.Plan,
    ) {
        emit(AgentEvent.CompactionStarted(plan.reason))
        val result = compactor.compact(
            sessionId = config.sessionId,
            messages = prepared.messages,
            plan = plan,
            endpoint = config.endpoint,
            model = config.model,
            previous = prepared.digest,
        )
        when (result) {
            is Compactor.Result.Compacted -> emit(
                AgentEvent.CompactionFinished(
                    result.digest,
                    result.archivedMessages,
                    result.tokensFreed,
                ),
            )

            is Compactor.Result.Failed -> {
                // Summarising failed. Trim anyway rather than send a request
                // that cannot fit; the placeholder digest tells the agent the
                // detail is still recoverable through conversation_search.
                val fallback = compactor.emergencyTrim(
                    config.sessionId, plan, result.message,
                    messages = prepared.messages, previous = prepared.digest,
                )
                if (fallback is Compactor.Result.Compacted) {
                    emit(
                        AgentEvent.CompactionFinished(
                            fallback.digest,
                            fallback.archivedMessages,
                            fallback.tokensFreed,
                        ),
                    )
                }
            }

            is Compactor.Result.Skipped -> Unit
        }
    }

    /* ----------------------------------------------------------- preparation */

    private class Prepared(
        val systemPrompt: String,
        /** Where the per-request part of [systemPrompt] begins; see SystemPrompt.Parts. */
        val systemStableLength: Int,
        val messages: List<Message>,
        val digest: ConversationDigest?,
        val accounting: ContextBudget.Accounting,
    )

    private suspend fun prepare(config: TurnConfig, latestUserText: String?): Prepared {
        // Every live message: compaction keeps the window to the budget, by
        // tokens. Counted whole, and with every call answered.
        val live = Transcript.answered(countedWhole(store.window(config.sessionId)))
        val digest = store.latestDigest(config.sessionId)
        val memory = projectMemory(config)
        val toolSchemaTokens = registry.declarationTokens(config.agent)

        val session = store.getSession(config.sessionId)
        val stats = store.stats(config.sessionId)

        val inputs =
            SystemPrompt.Inputs(
                agent = config.agent,
                model = config.model,
                layout = layout,
                policy = policy,
                workspace = config.workspace,
                tools = registry.declarationsFor(config.agent),
                thinkingMode = config.thinkingMode,
                capabilities = cachedCapabilities(),
                device = deviceState(),
                contextState = SystemPrompt.ContextState(
                    usedTokens = stats.liveTokens.toInt(),
                    budgetTokens = config.model.promptBudget,
                    compactions = session?.compactions ?: 0,
                    archivedMessages = stats.archivedMessages,
                ),
                projectMemory = memory,
                openFiles = runCatching { config.ui?.openFiles() ?: emptyList() }
                    .getOrDefault(emptyList()),
                activeFile = runCatching { config.ui?.activeFile() }.getOrNull(),
                terminals = terminalStates(),
                userLanguage = config.userLanguage,
            )

        // The prompt tells the model how full its window is. That number used
        // to count the conversation alone against the window less the reply,
        // so it left out the prompt and the tool schemas — the part that
        // compaction cannot shrink — and read low by a fifth on a small model.
        // Measured here on a draft instead, it is the fraction the compaction
        // threshold uses. The second build only changes that one line.
        val draft = SystemPrompt.build(inputs)
        val measured = budget.account(
            draft, toolSchemaTokens, digest?.render(), memory,
            live, config.model, config.model.maxOutputTokens,
        )
        val parts = SystemPrompt.parts(
            inputs.copy(
                contextState = inputs.contextState.copy(
                    usedTokens = measured.promptTokens,
                    budgetTokens = measured.promptBudget,
                ),
            ),
        )
        val systemPrompt = parts.text

        // Prompt order: digest, recalled history, live window. The digest is
        // pinned first so it is never what gets dropped.
        val prefix = buildList {
            digest?.let { add(compactor.asMessage(config.sessionId, it)) }
            if (latestUserText != null) {
                val provisional = budget.account(
                    systemPrompt, toolSchemaTokens, digest?.render(), memory,
                    live, config.model, config.model.maxOutputTokens,
                )
                compactor.recall(
                    config.sessionId,
                    latestUserText,
                    budget.recallBudget(provisional),
                )?.let { add(it) }
            }
        }

        val messages = prefix + live
        return Prepared(
            systemPrompt = systemPrompt,
            systemStableLength = parts.stableLength,
            messages = messages,
            digest = digest,
            accounting = budget.account(
                systemPrompt = systemPrompt,
                toolSchemaTokens = toolSchemaTokens,
                digest = digest?.render(),
                memory = memory,
                messages = messages,
                model = config.model,
                maxOutputTokens = config.model.maxOutputTokens,
            ),
        )
    }

    /**
     * [messages] with each tool result's tokens counted whole. Earlier builds
     * counted a result by its 400-character excerpt, so a chat of tool calls
     * read as a fraction of its size and compacted only once the provider
     * refused it. A low count is corrected here and stored; each message is
     * looked at once per process.
     */
    private suspend fun countedWhole(messages: List<Message>): List<Message> = messages.map { message ->
        if (message.role != Role.TOOL || !countChecked.add(message.id)) return@map message
        val whole = store.estimateTokens(message)
        if (whole <= message.tokens) return@map message
        runCatching { store.recount(message.id, whole) }
            .onFailure { Log.w(TAG, "could not store the count of ${message.id}: ${it.message}") }
        message.copy(tokens = whole)
    }

    /**
     * Live terminals for the prompt's environment block.
     *
     * Read from the registry rather than through the UI bridge, so a subagent
     * — which has no UI — still sees the jobs it started. Cheap: the per
     * session kernel lookup behind this is cached for a fraction of a second,
     * and it is asked for once per iteration.
     */
    private fun terminalStates(): List<EnvironmentContext.Terminal> {
        val registry = terminals ?: return emptyList()
        return runCatching {
            registry.list().map { snapshot ->
                EnvironmentContext.Terminal(
                    id = snapshot.id,
                    mine = snapshot.owner == TerminalSessions.Owner.AGENT,
                    command = snapshot.command,
                    cwd = snapshot.cwd ?: snapshot.workingDir,
                    running = snapshot.state == TerminalSessions.State.RUNNING,
                    exitCode = snapshot.exitCode,
                    runningForMs = snapshot.runningForMs,
                    foreground = snapshot.foreground,
                    lastLine = snapshot.lastLine,
                    fullScreenProgram = snapshot.fullScreenProgram,
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Chat titles, for naming the chat's transcript in the terminal panel; looked up once per chat. */
    private val transcriptTitles = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Where this chat's commands are shown as they run: its "Agent" terminal
     * in the terminal panel. Not for a subagent, whose chat nobody watches.
     */
    private suspend fun echoFor(config: TurnConfig): ExecEcho? {
        if (config.ui == null) return null
        val registry = terminals ?: return null
        val title = transcriptTitles[config.sessionId]
            ?: runCatching { store.getSession(config.sessionId)?.title }.getOrNull().orEmpty()
                .also { transcriptTitles[config.sessionId] = it }
        return registry.transcriptEcho(config.sessionId, title, config.workspace)
    }

    /**
     * Project instructions plus remembered facts.
     *
     * A project's own instruction file wins over remembered facts: it is
     * version-controlled and maintained deliberately, while memory accumulates.
     */
    private suspend fun projectMemory(config: TurnConfig): String? {
        if (!config.settings.context.includeProjectMemory) return null

        // Its beginning when it is long, with where the rest is said.
        val instructions = ProjectInstructions.load(config.workspace)
        val remembered = runCatching { store.memoryList(MemoryScopes.project(config.workspace)) }.getOrDefault(emptyMap())
        val global = runCatching { store.memoryList(MemoryScopes.GLOBAL) }.getOrDefault(emptyMap())

        if (instructions == null && remembered.isEmpty() && global.isEmpty()) return null

        return buildString {
            instructions?.let {
                appendLine("From ${it.name}:")
                appendLine()
                appendLine(it.text.trim())
                it.note()?.let { note ->
                    appendLine()
                    appendLine(note)
                }
            }
            if (remembered.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("Remembered about this project (memory_write):")
                appendFacts(remembered)
            }
            // Facts about the user and their setup hold in every project:
            // the language they want, how they like changes made. They used
            // to be kept but never shown, so nothing saved there had effect.
            if (global.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("Remembered for all projects (memory_write with global: true):")
                appendFacts(global)
            }
        }
    }

    /** One line per fact, up to [MAX_MEMORY_CHARS]; past that a count and where the rest are. */
    private fun StringBuilder.appendFacts(facts: Map<String, String>) {
        var used = 0
        var shown = 0
        for ((key, value) in facts) {
            val line = "- $key: $value"
            if (used + line.length > MAX_MEMORY_CHARS) break
            appendLine(line)
            used += line.length + 1
            shown++
        }
        if (shown < facts.size) {
            appendLine("- (${facts.size - shown} more not shown; memory_read lists every one)")
        }
    }

    /* -------------------------------------------------------------- streaming */

    private class TurnOutcome(
        val assistantMessageId: String,
        val toolUses: List<Block.ToolUse>,
        val error: LlmError?,
        /** The reply stopped at its output limit: a call it was writing may be incomplete. */
        val cutOff: Boolean = false,
    )

    private suspend fun streamAssistantTurn(
        collector: FlowCollector<AgentEvent>,
        config: TurnConfig,
        prepared: Prepared,
        iteration: Int,
    ): TurnOutcome {
        val messageId = ConversationStore.newId("m")

        store.append(
            Message(
                id = messageId,
                sessionId = config.sessionId,
                role = Role.ASSISTANT,
                status = MessageStatus.STREAMING,
                model = config.model.id,
            ),
        )
        collector.emit(AgentEvent.AssistantStarted(messageId, iteration))

        val text = StringBuilder()
        val thinking = StringBuilder()
        var thinkingSignature: String? = null
        var thinkingRedacted = false
        var reasoningTokens = 0

        // Tool calls arrive interleaved and fragmented. Assemble by call id and
        // keep the order the model emitted them in: results must line up with
        // calls positionally for some providers.
        val toolOrder = mutableListOf<String>()
        val toolNames = mutableMapOf<String, String>()
        val toolArgs = mutableMapOf<String, StringBuilder>()
        val progressAt = HashMap<String, Long>()

        var error: LlmError? = null
        var finishReason: String? = null
        var promptTokensReported = 0
        var lastPersist = 0L

        val request = CompletionRequest(
            endpoint = config.endpoint,
            model = config.model,
            apiKey = null, // resolved by LlmClient from the secret store
            system = prepared.systemPrompt,
            systemStableLength = prepared.systemStableLength,
            messages = prepared.messages,
            tools = registry.declarationsFor(config.agent),
            thinking = config.thinkingMode,
            reasoningEffort = config.reasoningEffort,
            maxOutputTokens = config.model.maxOutputTokens,
            stream = true,
        )

        try {
            llm.stream(request).collect { event ->
                when (event) {
                    is StreamEvent.TextDelta -> {
                        text.append(event.text)
                        collector.emit(AgentEvent.TextDelta(messageId, event.text))
                    }

                    is StreamEvent.ThinkingDelta -> {
                        thinking.append(event.text)
                        collector.emit(AgentEvent.ThinkingDelta(messageId, event.text))
                    }

                    is StreamEvent.TextWasThinking -> if (text.isNotEmpty()) {
                        if (thinking.isNotEmpty()) thinking.append("\n\n")
                        thinking.append(text.toString().trim())
                        text.setLength(0)
                        collector.emit(AgentEvent.TextWasThinking(messageId))
                    }

                    is StreamEvent.ThinkingSignature -> {
                        // Opaque provider attestation. Must round-trip or the next
                        // request is rejected; never shown to the user.
                        thinkingSignature = event.signature
                        thinkingRedacted = event.redacted
                    }

                    is StreamEvent.ToolCallStart -> {
                        if (event.callId !in toolOrder) toolOrder += event.callId
                        toolNames[event.callId] = event.name
                        toolArgs.getOrPut(event.callId) { StringBuilder() }
                        collector.emit(
                            AgentEvent.ToolCallStarted(messageId, event.callId, event.name, ""),
                        )
                    }

                    is StreamEvent.ToolCallArgsDelta -> {
                        if (event.callId !in toolOrder) toolOrder += event.callId
                        val args = toolArgs.getOrPut(event.callId) { StringBuilder() }.append(event.fragment)
                        // How far along the model is, a few times a second: the
                        // fragments themselves are far too many to pass on. All of
                        // the first few, though, where the path or the command is:
                        // a provider that holds a long value back until it is
                        // complete (Kiro sends a file's content after a pause as
                        // long as the writing, the path before it) left the card
                        // showing the first eight bytes and no path the whole time.
                        val now = System.currentTimeMillis()
                        if (args.length <= PROGRESS_EVERY_FRAGMENT_CHARS ||
                            now - (progressAt[event.callId] ?: 0L) >= PROGRESS_INTERVAL_MS
                        ) {
                            progressAt[event.callId] = now
                            collector.emit(
                                AgentEvent.ToolCallProgress(
                                    messageId, event.callId, args.length, args.substring(0, minOf(args.length, PROGRESS_HEAD_CHARS)),
                                ),
                            )
                        }
                    }

                    is StreamEvent.ToolCallEnd -> Unit

                    is StreamEvent.Usage -> {
                        reasoningTokens = event.reasoningTokens
                        promptTokensReported = event.promptTokens
                    }

                    is StreamEvent.Failed -> error = event.error

                    is StreamEvent.Done -> finishReason = event.finishReason
                }

                // Periodic persistence, not per token: writing to SQLite at token
                // rate would cost more than the streaming itself.
                val now = System.currentTimeMillis()
                if (now - lastPersist > PERSIST_INTERVAL_MS) {
                    lastPersist = now
                    store.update(
                        id = messageId,
                        content = snapshotBlocks(
                            text, thinking, thinkingSignature, thinkingRedacted,
                            reasoningTokens, toolOrder, toolNames, toolArgs,
                        ),
                        recountTokens = false,
                        // Indexed once, by the final save below.
                        index = false,
                    )
                }
            }
        } catch (stopped: CancellationException) {
            // Stopped: the reply stays as far as it came, marked as stopped,
            // and indexed — not as the last periodic save left it, up to
            // PERSIST_INTERVAL_MS short of what the user watched arrive and
            // still "streaming" until the next start repaired it.
            withContext(NonCancellable) {
                runCatching {
                    store.update(
                        id = messageId,
                        content = snapshotBlocks(
                            text, thinking, thinkingSignature, thinkingRedacted,
                            reasoningTokens, toolOrder, toolNames, toolArgs,
                        ),
                        status = MessageStatus.ABORTED,
                        recountTokens = true,
                    )
                }.onFailure { Log.w(TAG, "could not save the stopped reply $messageId: ${it.message}") }
            }
            throw stopped
        }

        val failure = error
        // How it ended, said under it: a reply cut at its output limit, or
        // stopped by a filter, used to read as finished, and a failed one
        // lost its reason once the chat was reopened.
        val notice = if (failure != null) {
            Block.Notice(Block.Notice.FAILED, text = failure.message)
        } else {
            StopReasons.notice(finishReason, config.model.maxOutputTokens)
        }
        val blocks = snapshotBlocks(
            text, thinking, thinkingSignature, thinkingRedacted,
            reasoningTokens, toolOrder, toolNames, toolArgs,
        ) + listOfNotNull(notice)

        store.update(
            id = messageId,
            content = blocks,
            status = if (failure != null) MessageStatus.ERROR else MessageStatus.COMPLETE,
            recountTokens = true,
        )

        if (thinking.isNotEmpty()) {
            collector.emit(AgentEvent.ThinkingFinished(messageId, reasoningTokens))
        }

        // Correct the token estimator against the provider's own count. After
        // two or three turns the context meter tracks the provider closely,
        // which is what makes budgeting on an estimate safe.
        if (promptTokensReported > 0) {
            NativeCore.calibrateTokens(prepared.accounting.promptTokens, promptTokensReported)
        }

        collector.emit(
            AgentEvent.MessageCompleted(
                Message(
                    id = messageId,
                    sessionId = config.sessionId,
                    role = Role.ASSISTANT,
                    status = if (failure != null) MessageStatus.ERROR else MessageStatus.COMPLETE,
                    content = blocks,
                    model = config.model.id,
                    errorMessage = failure?.message,
                ),
            ),
        )

        return TurnOutcome(
            assistantMessageId = messageId,
            toolUses = blocks.filterIsInstance<Block.ToolUse>(),
            error = failure,
            cutOff = notice?.kind == Block.Notice.OUTPUT_LIMIT,
        )
    }

    /**
     * Current content as blocks.
     *
     * Reasoning first: both protocols expect it ahead of the text it produced,
     * and Anthropic rejects an assistant message where it comes later.
     */
    private fun snapshotBlocks(
        text: StringBuilder,
        thinking: StringBuilder,
        signature: String?,
        redacted: Boolean,
        reasoningTokens: Int,
        toolOrder: List<String>,
        toolNames: Map<String, String>,
        toolArgs: Map<String, StringBuilder>,
    ): List<Block> = buildList {
        if (thinking.isNotEmpty() || (redacted && signature != null)) {
            add(
                Block.Thinking(
                    text = thinking.toString(),
                    redacted = redacted,
                    signature = signature,
                    tokens = reasoningTokens,
                ),
            )
        }
        if (text.isNotEmpty()) add(Block.Text(text.toString()))
        for (callId in toolOrder) {
            add(
                Block.ToolUse(
                    callId = callId,
                    name = toolNames[callId] ?: "unknown",
                    argumentsJson = toolArgs[callId]?.toString().orEmpty(),
                ),
            )
        }
    }

    /* ---------------------------------------------------------- tool calling */

    /**
     * Run the calls the model made, then append their results as one message.
     *
     * Read-only calls run concurrently; anything that mutates runs one at a
     * time. Two concurrent edits to the same file, or two `git commit`s,
     * interleave in ways that are hard to reason about and impossible to review.
     */
    private suspend fun executeToolCalls(
        collector: FlowCollector<AgentEvent>,
        config: TurnConfig,
        assistantMessageId: String,
        toolUses: List<Block.ToolUse>,
        /** The reply stopped at its output limit; see [runOne]. */
        cutOff: Boolean = false,
    ) {
        val results = mutableListOf<Block.ToolResult>()
        val touched = mutableListOf<String>()
        val parallelLimit = config.agent.maxParallelTools.coerceIn(1, 8)
        // Running output and subagents' runs, posted by tools from wherever
        // they run. Each event replaces the one before it for its call, so
        // overflow drops the oldest and loses nothing that matters.
        val output = Channel<AgentEvent>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        for (group in groupForParallelism(toolUses, parallelLimit)) {
            // From here the card's clock counts the call's own time, not the
            // model's writing nor the calls before it.
            for (use in group) collector.emit(AgentEvent.ToolCallRunning(use.callId))
            val executed = coroutineScope {
                val work = async {
                    if (group.size == 1) {
                        listOf(runOne(config, assistantMessageId, group.first(), output, cutOff))
                    } else {
                        group
                            .map { use -> async { runOne(config, assistantMessageId, use, output, cutOff) } }
                            .map { it.await() }
                    }
                }
                // Passed on while the group runs, from this coroutine: the
                // flow's own, which is the only one allowed to emit.
                while (!work.isCompleted) {
                    select<Unit> {
                        work.onJoin {}
                        output.onReceive { collector.emit(it) }
                    }
                }
                work.await()
            }

            // Emitted here, sequentially in the flow's own coroutine: emitting
            // from inside the async blocks above would violate flow context.
            for (outcome in executed) {
                collector.emit(outcome.event)
                results += outcome.block
                touched += outcome.touchedPaths
            }
        }

        if (results.isNotEmpty()) {
            val message = store.append(
                Message(
                    id = ConversationStore.newId("m"),
                    sessionId = config.sessionId,
                    role = Role.TOOL,
                    content = results,
                ),
            )
            collector.emit(AgentEvent.MessageCompleted(message))
        }
        if (touched.isNotEmpty()) {
            collector.emit(AgentEvent.FilesChanged(touched.distinct()))
        }
    }

    /**
     * Split calls into runs that may execute together.
     *
     * A group is formed only from consecutive parallel-safe calls, so ordering
     * between a read and a subsequent write is preserved. Whether a call is
     * safe can depend on its arguments: a subagent dispatch is, for the
     * Explorer only ([ToolRegistry.parallelSafe]).
     */
    private fun groupForParallelism(
        toolUses: List<Block.ToolUse>,
        limit: Int,
    ): List<List<Block.ToolUse>> {
        val groups = mutableListOf<MutableList<Block.ToolUse>>()
        // Whether the last group is made of safe calls: one unsafe call is a group of its own.
        var lastIsSafe = false
        for (use in toolUses) {
            val safe = registry.parallelSafe(use.name, use.argumentsJson)
            val last = groups.lastOrNull()
            if (safe && last != null && lastIsSafe && last.size < limit) {
                last += use
            } else {
                groups += mutableListOf(use)
                lastIsSafe = safe
            }
        }
        return groups
    }

    private class CallOutcome(
        val block: Block.ToolResult,
        val event: AgentEvent,
        val touchedPaths: List<String>,
    )

    private suspend fun runOne(
        config: TurnConfig,
        assistantMessageId: String,
        use: Block.ToolUse,
        output: SendChannel<AgentEvent>,
        cutOff: Boolean = false,
    ): CallOutcome {
        val started = System.currentTimeMillis()

        store.recordToolCall(
            ToolCallRecord(
                id = use.callId,
                sessionId = config.sessionId,
                messageId = assistantMessageId,
                name = use.name,
                argumentsJson = use.argumentsJson,
                status = ToolCallStatus.RUNNING,
                startedAt = started,
            ),
        )

        val ctx = ToolContext(
            sessionId = config.sessionId,
            changesSessionId = config.changesSessionId ?: config.sessionId,
            workspace = config.workspace,
            layout = layout,
            policy = policy,
            shell = shell,
            store = store,
            governor = governor,
            agent = config.currentAgent(),
            settings = config.settings,
            toolCallId = use.callId,
            approvals = config.approvals,
            terminals = terminals,
            webFetcher = webFetcher,
            packages = packages,
            ui = config.ui,
            // Nobody watches a subagent's transcript.
            onOutput = if (config.ui != null) {
                { rows -> output.trySend(AgentEvent.ToolOutput(use.callId, rows)) }
            } else {
                null
            },
            onSubagent = if (config.ui != null) {
                { run -> output.trySend(AgentEvent.SubagentProgress(use.callId, run)) }
            } else {
                null
            },
            dispatchSubagent = config.dispatchSubagent,
            echo = echoFor(config),
        )

        // A call the output limit cut off is not JSON. Told as "could not
        // parse", the model wrote the same call again, was cut at the same
        // place, and went round until the iteration cap.
        val result = if (cutOff && (use.argumentsJson.isBlank() || !registry.parses(use.argumentsJson))) {
            ToolResult.error(
                "Not run: your reply reached its output limit (${config.model.maxOutputTokens} tokens) " +
                    "while this call was being written, so its arguments were cut off. Send less in one " +
                    "call: write a long file in parts — fs_write its beginning, then add the rest with " +
                    "fs_edit, a part per call — and split long commands or scripts the same way.",
            )
        } else {
            registry.execute(use.name, use.argumentsJson, ctx)
        }
        val duration = System.currentTimeMillis() - started

        // The command list in the prompt is cached for a minute; after an
        // install or removal the next iteration must see the new state, not
        // tell the model python is still missing.
        if (use.name == "pkg_install" || use.name == "pkg_remove") invalidateCapabilities()

        val (content, blobId, truncated) = spillIfOversized(config, use, result)

        val status = if (result.isError) ToolCallStatus.ERROR else ToolCallStatus.OK

        store.updateToolCall(
            id = use.callId,
            status = status,
            exitCode = result.exitCode,
            preview = content.take(300),
            blobId = blobId,
            tokens = NativeCore.estimateTokens(content),
            ended = true,
        )

        return CallOutcome(
            block = Block.ToolResult(
                callId = use.callId,
                name = use.name,
                content = content,
                isError = result.isError,
                blobId = blobId,
                truncated = truncated,
                durationMs = duration,
                diff = (result.display as? ToolDisplay.Diff)?.let(::diffSummary),
                subagent = (result.display as? ToolDisplay.Subagent)?.run,
            ),
            event = AgentEvent.ToolCallFinished(
                callId = use.callId,
                name = use.name,
                status = status,
                result = result.copy(content = content, blobId = blobId, truncated = truncated),
                durationMs = duration,
            ),
            touchedPaths = result.touchedPaths,
        )
    }

    /**
     * The persisted form of an edit's diff. Capped: a rewritten 3000-line file
     * would otherwise store all of it in the message row, and no one reviews
     * that in a chat bubble — the editor and the changes bar show the whole.
     */
    private fun diffSummary(diff: ToolDisplay.Diff): Block.DiffSummary {
        val lines = diff.unified.lineSequence().toList()
        val cut = lines.size > MAX_DIFF_LINES || diff.unified.length > MAX_DIFF_CHARS
        val kept = if (cut) {
            lines.take(MAX_DIFF_LINES).joinToString("\n").take(MAX_DIFF_CHARS)
        } else {
            diff.unified
        }
        return Block.DiffSummary(
            path = diff.path,
            unified = kept,
            added = diff.addedLines,
            removed = diff.removedLines,
            snapshotId = diff.snapshotId,
            truncated = cut,
        )
    }

    /**
     * Keep a single tool result from eating the window.
     *
     * The cap tightens as the context fills: a 4000-token build log early in a
     * session is affordable, and the same log at 85% full would trigger a
     * compaction that discards conversation to make room for a log.
     */
    private suspend fun spillIfOversized(
        config: TurnConfig,
        use: Block.ToolUse,
        result: ToolResult,
    ): Triple<String, String?, Boolean> {
        val accounting = lastAccounting
        val cap = if (accounting != null) {
            budget.toolResultBudget(accounting)
        } else {
            config.settings.context.toolResultTokenCap
        }

        if (NativeCore.estimateTokens(result.content) <= cap) {
            return Triple(result.content, result.blobId, result.truncated)
        }

        val clamped = NativeCore.clampTokens(result.content, cap)
        val blobId = result.blobId ?: runCatching {
            store.putBlob(
                data = result.content,
                kind = "tool_output",
                label = "${use.name} (${result.content.length} chars)",
                sessionId = config.sessionId,
            )
        }.getOrNull()

        val content = clamped.text + if (blobId != null) {
            "\n\n[Full output stored as $blobId — page through it with " +
                "conversation_read(blobId: \"$blobId\").]"
        } else {
            ""
        }
        return Triple(content, blobId, true)
    }

    /**
     * Most recent accounting, used to size tool-result budgets.
     *
     * Held as state rather than threaded through four call layers: the cap is
     * advisory, and a value from earlier in the same turn is close enough.
     */
    @Volatile
    private var lastAccounting: ContextBudget.Accounting? = null

    /* ----------------------------------------------------------- environment */

    private var capabilitiesCache: Map<String, String?>? = null
    private var capabilitiesCachedAt = 0L

    /**
     * `which` over every interesting command is ~27 process spawns. Doing that
     * every turn would add real latency for information that changes only when
     * a package is installed.
     */
    private suspend fun cachedCapabilities(): Map<String, String?> {
        val now = System.currentTimeMillis()
        capabilitiesCache?.let { cached ->
            if (now - capabilitiesCachedAt < CAPABILITIES_TTL_MS) return cached
        }
        val fresh = shell.capabilities()
        capabilitiesCache = fresh
        capabilitiesCachedAt = now
        return fresh
    }

    /** Call after installing anything. */
    fun invalidateCapabilities() {
        capabilitiesCache = null
    }

    private fun deviceState(): SystemPrompt.DeviceState {
        val snapshot = governor.policy.value
        return SystemPrompt.DeviceState(
            androidRelease = Build.VERSION.RELEASE ?: "?",
            apiLevel = Build.VERSION.SDK_INT,
            arch = Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
            thermalDescription = snapshot.reason,
            buildsAllowed = snapshot.allowBuild,
            freeStorageBytes = layout.freeBytes(),
        )
    }

    private fun deriveTitle(text: String): String {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: return "New session"
        return if (firstLine.length <= 60) firstLine else firstLine.take(57) + "…"
    }

    companion object {
        private const val TAG = "AgentLoop"

        /** Tool messages whose count [countedWhole] has looked at, in this process. A loop lasts a turn; this lasts longer. */
        private val countChecked: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** How often a streaming message is written to SQLite. */
        private const val PERSIST_INTERVAL_MS = 400L

        /** How often a call being written reports its progress, and how much of it goes with the report. */
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val PROGRESS_HEAD_CHARS = 1_024

        /** Up to this much of a call's arguments, every fragment is reported; see the throttle. */
        private const val PROGRESS_EVERY_FRAGMENT_CHARS = 200

        private const val CAPABILITIES_TTL_MS = 60_000L

        /** Ceiling on a diff stored for the transcript; see [diffSummary]. */
        private const val MAX_DIFF_LINES = 400
        private const val MAX_DIFF_CHARS = 48 * 1024

        /** Remembered facts put in the prompt, per scope. */
        private const val MAX_MEMORY_CHARS = 6_000
    }
}
