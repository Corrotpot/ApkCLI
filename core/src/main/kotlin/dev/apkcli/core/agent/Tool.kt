package dev.apkcli.core.agent

import dev.apkcli.core.model.AgentDefinition
import dev.apkcli.core.model.AppSettings
import dev.apkcli.core.runtime.EnvLayout
import dev.apkcli.core.runtime.ExecPolicy
import dev.apkcli.core.runtime.ShellRunner
import dev.apkcli.core.sched.LoadGovernor
import dev.apkcli.core.sched.TaskClass
import dev.apkcli.core.store.ConversationStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * Everything a tool is allowed to touch.
 *
 * Passed in rather than injected so that a subagent can be handed a context
 * with a different workspace, a different agent definition and a narrower
 * approval gate without any tool knowing that happened.
 */
class ToolContext(
    val sessionId: String,
    val workspace: File,
    val layout: EnvLayout,
    val policy: ExecPolicy,
    val shell: ShellRunner,
    val store: ConversationStore,
    val governor: LoadGovernor,
    val agent: AgentDefinition,
    val settings: AppSettings,
    val toolCallId: String,
    val approvals: ApprovalGate,
    /**
     * Every live terminal: the ones the user opened and the ones the agent
     * left running. Null when the native core is unavailable, so there is no
     * PTY to run anything on.
     */
    val terminals: dev.apkcli.core.runtime.TerminalSessions? = null,
    val webFetcher: dev.apkcli.core.web.WebFetcher? = null,
    /** Null where installed binaries could not run (the Play build) or the ABI has no Termux build. */
    val packages: dev.apkcli.core.pkg.PackageManager? = null,
    /** Null for a subagent, which has no UI of its own. */
    val ui: UiBridge? = null,
    /**
     * The latest output of this call while it runs, for the transcript: the
     * last lines its terminal shows, with their colours, replacing the
     * previous ones. Not a stream the model sees — it gets the result. Safe
     * to call from any thread; it only queues. Null where nobody is watching
     * (a subagent).
     */
    val onOutput: ((List<dev.apkcli.core.runtime.TerminalBuffer.Row>) -> Unit)? = null,
    /**
     * A subagent this call dispatched, as its run stands: for its card in
     * the transcript, replacing the previous snapshot. Like [onOutput], safe
     * from any thread and only queues; null where nobody is watching.
     */
    val onSubagent: ((dev.apkcli.core.model.SubagentRun) -> Unit)? = null,
    /** Dispatch a subagent. Null when the current agent may not. */
    val dispatchSubagent: (suspend (SubagentRequest) -> SubagentOutcome)? = null,
    /**
     * The chat's "Agent" terminal in the terminal panel, for the commands
     * this call runs and what they print. Null where nobody is watching.
     */
    val echo: dev.apkcli.core.runtime.ExecEcho? = null,
    /**
     * The session file edits are recorded under, for the chat's change list
     * and its checkpoints: the dispatching chat's for a subagent, otherwise
     * [sessionId].
     */
    val changesSessionId: String = sessionId,
) {
    /** Resolve a model-supplied path, refusing anything outside the sandbox. */
    fun resolve(path: String): File? = layout.resolveInside(workspace, path)

    /** Display form for messages back to the model: short and stable. */
    fun display(file: File): String {
        val base = runCatching { workspace.canonicalPath }.getOrNull()
        val target = runCatching { file.canonicalPath }.getOrNull() ?: file.path
        return if (base != null && target.startsWith("$base/")) {
            target.removePrefix("$base/")
        } else {
            layout.shorten(file)
        }
    }
}

data class SubagentRequest(
    val agentId: String,
    val prompt: String,
    val contextFiles: List<String> = emptyList(),
    val maxIterations: Int = 20,
    /** Its run as it changes, a few times a second at most; null where nobody watches. */
    val onProgress: ((dev.apkcli.core.model.SubagentRun) -> Unit)? = null,
)

/**
 * What a subagent came back with: [text] for the model that dispatched it,
 * and [run], what it did, for the card. [run] is null when it never started
 * (an unknown agent, no model).
 */
class SubagentOutcome(val text: String, val run: dev.apkcli.core.model.SubagentRun?)

/**
 * The few things a tool legitimately needs from the UI.
 *
 * Narrow on purpose. Tools reaching into the UI is how a core module ends up
 * depending on Compose; this is an interface the app implements, and a subagent
 * simply gets null.
 */
interface UiBridge {
    /**
     * Open a file in the editor, optionally scrolled to a line.
     *
     * @return null once the editor has the file open, otherwise why it does
     *   not — in English, for the model. The editor can refuse (a binary file,
     *   one over its size limit) or not be there to ask; reporting "opened"
     *   regardless had the agent tell the user to look at nothing.
     */
    suspend fun openInEditor(path: String, line: Int? = null): String?

    /** Replace the agent's visible task list. */
    suspend fun setTasks(items: List<Task>)

    suspend fun currentTasks(): List<Task>

    /** Files currently open in the editor, most recently focused first. */
    suspend fun openFiles(): List<String>

    /**
     * The file the user is looking at — what "this file" refers to.
     *
     * Defaulted to the most recently focused open file, which is what the
     * ordering of [openFiles] already means; an implementation that tracks
     * focus exactly should override it.
     */
    suspend fun activeFile(): String? = openFiles().firstOrNull()

    // No `ask`: questions go in the reply, answered in the next message.
    // See the note where the `ask_user` tool used to be, in AgentTools.

    data class Task(
        val text: String,
        val done: Boolean = false,
        val active: Boolean = false,
    )
}

/**
 * Gate for tools whose permission is `ASK`.
 *
 * An interface because the answer comes from three different places: a dialog
 * in the UI, an auto-approve rule, or a subagent's inherited policy.
 */
interface ApprovalGate {
    /**
     * @param callId the tool call being gated, so the UI can mark that row
     *   as waiting rather than running. This is how the "awaiting approval"
     *   state reaches the screen: it cannot be an [AgentEvent], because
     *   approval is requested from inside a tool coroutine that has no
     *   `FlowCollector` to emit into.
     * @param summary one line describing the action, shown in the prompt.
     * @param detail the call's arguments, whole: what the sheet shows is what
     *   runs, however long the command.
     * @param requester who asks, when it is not the chat's own agent: a
     *   subagent's name. Null for the agent the user is talking to.
     * @return true to proceed.
     */
    suspend fun request(
        callId: String,
        toolName: String,
        summary: String,
        detail: String,
        destructive: Boolean,
        requester: String? = null,
    ): Boolean

    /** Approve everything. Used by read-only agents, which never ask. */
    object AlwaysAllow : ApprovalGate {
        override suspend fun request(
            callId: String,
            toolName: String,
            summary: String,
            detail: String,
            destructive: Boolean,
            requester: String?,
        ) = true
    }

    object AlwaysDeny : ApprovalGate {
        override suspend fun request(
            callId: String,
            toolName: String,
            summary: String,
            detail: String,
            destructive: Boolean,
            requester: String?,
        ) = false
    }

    /**
     * Another gate, asked on behalf of [label].
     *
     * A subagent's calls are approved in its parent's dialog — its own
     * session has nobody watching — and "Write Main.kt" alone would not say
     * that a subagent is the one asking. [onAsk] and [onAnswer] let its card
     * show the step waiting and how it was answered.
     */
    class Labelled(
        private val inner: ApprovalGate,
        private val label: String,
        private val onAsk: (callId: String) -> Unit = {},
        private val onAnswer: (callId: String, allowed: Boolean) -> Unit = { _, _ -> },
    ) : ApprovalGate {
        override suspend fun request(
            callId: String,
            toolName: String,
            summary: String,
            detail: String,
            destructive: Boolean,
            requester: String?,
        ): Boolean {
            onAsk(callId)
            val allowed = inner.request(callId, toolName, summary, detail, destructive, requester ?: label)
            onAnswer(callId, allowed)
            return allowed
        }
    }
}

/**
 * Rich payload for the transcript, alongside the text the model receives.
 *
 * The model gets a diff as unified text; the user gets a syntax-highlighted
 * side-by-side with accept/reject. Both come from one tool call, so the display
 * form travels with the result rather than being reconstructed by the UI from a
 * parsed string.
 */
sealed interface ToolDisplay {

    data class Plain(val text: String) : ToolDisplay

    data class Diff(
        val path: String,
        val unified: String,
        val addedLines: Int,
        val removedLines: Int,
        /** Snapshot id, so the change can be reverted later from the UI. */
        val snapshotId: String? = null,
    ) : ToolDisplay

    data class FileContent(
        val path: String,
        val text: String,
        val firstLine: Int,
        val totalLines: Int,
        val truncated: Boolean,
    ) : ToolDisplay

    data class Matches(
        val query: String,
        val hits: List<Hit>,
        val filesScanned: Int,
        val truncated: Boolean,
    ) : ToolDisplay {
        data class Hit(val path: String, val line: Int, val text: String)
    }

    data class Listing(val root: String, val entries: List<Entry>) : ToolDisplay {
        data class Entry(val path: String, val isDir: Boolean, val size: Long)
    }

    data class Command(
        val command: String,
        val exitCode: Int?,
        val output: String,
        val durationMs: Long,
        val timedOut: Boolean,
    ) : ToolDisplay

    data class WebPage(
        val url: String,
        val title: String,
        val renderedWithJs: Boolean,
        val contentLength: Int,
    ) : ToolDisplay

    data class Tasks(val items: List<Item>) : ToolDisplay {
        data class Item(val text: String, val done: Boolean, val active: Boolean)
    }

    /** What a dispatched subagent did: its card, in place of the text the model got. */
    data class Subagent(val run: dev.apkcli.core.model.SubagentRun) : ToolDisplay

    /** `web_search` results, drawn as a list of links rather than a text dump. */
    data class SearchResults(
        val query: String,
        /** Where they came from, e.g. "DuckDuckGo" or the custom endpoint's host. */
        val engine: String,
        val results: List<Result>,
    ) : ToolDisplay {
        data class Result(val title: String, val url: String, val snippet: String)
    }
}

/**
 * What a tool returns.
 *
 * [content] is the only part the model sees. Keeping it separate from [display]
 * stops UI formatting from leaking into the prompt, which is a real cost: ANSI
 * codes, box-drawing characters and column padding are pure token waste.
 */
data class ToolResult(
    val content: String,
    val isError: Boolean = false,
    val display: ToolDisplay? = null,
    /** Set when the full output was spilled; the model pages it with `conversation_read`. */
    val blobId: String? = null,
    val truncated: Boolean = false,
    val exitCode: Int? = null,
    /** Files this call changed, for the editor to reload. */
    val touchedPaths: List<String> = emptyList(),
) {
    companion object {
        fun ok(content: String, display: ToolDisplay? = null) = ToolResult(content, display = display)

        fun error(message: String) = ToolResult(message, isError = true)

        /**
         * A refusal the model should not retry.
         *
         * Phrased as an instruction rather than an error, because an agent that
         * reads "Permission denied" will try `chmod` and then `sudo`; one that
         * reads "this tool is unavailable to this agent" will route around it.
         */
        fun refused(message: String) = ToolResult(message, isError = true)
    }
}

/** A tool the model can call. */
interface Tool {
    val name: String

    /**
     * Shown to the model. This is prompt text and is charged on every request,
     * so it earns its tokens: what it does, when to prefer it over a
     * neighbouring tool, and the failure modes worth knowing. No examples
     * unless the argument shape is genuinely non-obvious.
     */
    val description: String

    /** JSON Schema for the arguments. */
    val parameters: JsonObject

    /** False for anything that writes, executes or sends. Drives gating. */
    val readOnly: Boolean get() = false

    /**
     * Hard to undo. Whether it asks is the mode's, like any change; this is
     * what the approval sheet marks in red when it does.
     */
    val destructive: Boolean get() = false

    /**
     * Whether a supervised agent asks before running this. Anything that
     * changes something does, except the agent's own bookkeeping — its task
     * list and its memory — which is not a change to the user's work, and
     * asking about every one made supervision a stream of taps.
     */
    val supervised: Boolean get() = !readOnly

    /** Which pool this runs on. */
    val taskClass: TaskClass get() = TaskClass.BACKGROUND

    /**
     * Can several calls to this tool run at once in one turn?
     *
     * False for anything that mutates shared state: two concurrent edits to the
     * same file, or two `git commit`s, interleave badly.
     */
    val parallelSafe: Boolean get() = readOnly

    /**
     * Whether this call, with these arguments, may run beside others: for a
     * tool whose arguments decide whether it changes anything. Asked only
     * when [parallelSafe] is true.
     */
    fun parallelSafe(args: JsonObject): Boolean = true

    /**
     * Ceiling for one call, when the registry's default does not fit. Null
     * means [ToolRegistry.DEFAULT_TIMEOUT_MS]. For work that is long but
     * bounded — a package install over mobile data — not for open-ended work,
     * which belongs in `sh_background`.
     */
    val timeoutMs: Long? get() = null

    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

/* -------------------------------------------------------------------------
 * Schema DSL
 *
 * JSON Schema written as nested buildJsonObject calls is unreadable, and these
 * schemas are read often — they are the contract with the model and the first
 * thing to check when it passes the wrong shape.
 * ---------------------------------------------------------------------- */

object Schema {

    fun obj(
        required: List<String> = emptyList(),
        properties: JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { properties() }
        if (required.isNotEmpty()) {
            put("required", buildJsonArray { required.forEach { add(it) } })
        }
        // Rejecting unknown keys makes a typo in an argument name a validation
        // error the model can see and fix, instead of a silently ignored field.
        put("additionalProperties", false)
    }

    fun JsonObjectBuilder.string(
        name: String,
        description: String,
        enum: List<String>? = null,
        default: String? = null,
    ) = putJsonObject(name) {
        put("type", "string")
        put("description", description)
        enum?.let { values -> put("enum", buildJsonArray { values.forEach { add(it) } }) }
        default?.let { put("default", it) }
    }

    fun JsonObjectBuilder.integer(
        name: String,
        description: String,
        default: Int? = null,
        minimum: Int? = null,
        maximum: Int? = null,
    ) = putJsonObject(name) {
        put("type", "integer")
        put("description", description)
        default?.let { put("default", it) }
        minimum?.let { put("minimum", it) }
        maximum?.let { put("maximum", it) }
    }

    fun JsonObjectBuilder.boolean(
        name: String,
        description: String,
        default: Boolean? = null,
    ) = putJsonObject(name) {
        put("type", "boolean")
        put("description", description)
        default?.let { put("default", it) }
    }

    fun JsonObjectBuilder.stringArray(name: String, description: String) = putJsonObject(name) {
        put("type", "array")
        put("description", description)
        putJsonObject("items") { put("type", "string") }
    }

    fun JsonObjectBuilder.objectArray(
        name: String,
        description: String,
        required: List<String> = emptyList(),
        itemProperties: JsonObjectBuilder.() -> Unit,
    ) = putJsonObject(name) {
        put("type", "array")
        put("description", description)
        put("items", obj(required, itemProperties))
    }

    /** No arguments. */
    val empty: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
        put("additionalProperties", false)
    }

    fun emptyArray(): JsonArray = buildJsonArray {}
}
