package dev.apkcli.core.store

import android.util.Log
import dev.apkcli.core.model.Block
import dev.apkcli.core.model.ConversationDigest
import dev.apkcli.core.model.Message
import dev.apkcli.core.model.MessageHit
import dev.apkcli.core.model.MessageStatus
import dev.apkcli.core.model.Role
import dev.apkcli.core.model.Session
import dev.apkcli.core.model.SessionStats
import dev.apkcli.core.model.ToolCallRecord
import dev.apkcli.core.model.ToolCallStatus
import dev.apkcli.core.nativebridge.NativeCore
import dev.apkcli.core.runtime.EnvLayout
import dev.apkcli.core.sched.LoadGovernor
import dev.apkcli.core.sched.TaskClass
import dev.apkcli.core.util.bool
import dev.apkcli.core.util.double
import dev.apkcli.core.util.int
import dev.apkcli.core.util.intOrNull
import dev.apkcli.core.util.jsonOf
import dev.apkcli.core.util.long
import dev.apkcli.core.util.obj
import dev.apkcli.core.util.objects
import dev.apkcli.core.util.str
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import java.util.UUID

/**
 * Typed access to the SQLite conversation store that lives in the native core.
 *
 * Every method here is a suspend function dispatched onto a governed pool: the
 * underlying JNI calls block, and a 400-message window load on the main thread
 * is a visible stall. Reads are [TaskClass.INTERACTIVE] because the UI waits on
 * them; writes during streaming are [TaskClass.BACKGROUND].
 */
class ConversationStore(
    private val layout: EnvLayout,
    private val governor: LoadGovernor,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    private val blockListSerializer = ListSerializer(Block.serializer())

    @Volatile
    private var handle: Long = 0

    @Synchronized
    private fun handle(): Long {
        if (handle == 0L) {
            layout.db.mkdirs()
            handle = NativeCore.openStore(layout.conversationDb.absolutePath)
            Log.i(TAG, "store opened at ${layout.conversationDb.absolutePath}")
        }
        return handle
    }

    /**
     * Mark messages left mid-stream as aborted.
     *
     * A turn persists its assistant message as `streaming` and rewrites it as
     * deltas arrive. If the process dies in between — a crash, the low-memory
     * killer, the user force-stopping the app — the row stays `streaming`
     * forever, and the UI shows a spinner on a message that will never finish.
     *
     * Run once per process at startup, before any session is opened.
     */
    suspend fun repairInterruptedMessages(): Int = withContext(
        governor.dispatcherFor(TaskClass.BACKGROUND),
    ) {
        var repaired = 0
        for (session in listSessions(limit = 200, includeArchived = true)) {
            val stuck = window(session.id, limit = 600, includeArchived = true)
                .filter { it.status == MessageStatus.STREAMING }
            for (message in stuck) {
                // With its content, so it is indexed: a streaming message's
                // periodic saves leave the search text to its final save,
                // which a stopped or killed turn never made.
                update(id = message.id, content = message.content, status = MessageStatus.ABORTED, recountTokens = false)
                repaired++
            }
        }
        if (repaired > 0) Log.i(TAG, "marked $repaired interrupted message(s) as aborted")
        repaired
    }

    fun close() {
        if (handle != 0L) {
            NativeCore.closeStore(handle)
            handle = 0
        }
    }

    private suspend fun call(
        op: String,
        payload: JsonObject,
        taskClass: TaskClass = TaskClass.INTERACTIVE,
    ): JsonObject = withContext(governor.dispatcherFor(taskClass)) {
        NativeCore.storeCall(handle(), op, payload)
    }

    /* -------------------------------------------------------------- sessions */

    suspend fun createSession(
        title: String = "",
        workspace: String = "",
        agentId: String? = null,
        endpointId: String? = null,
        model: String? = null,
        id: String = newId("s"),
    ): Session {
        call(
            "session.create",
            jsonOf(
                "id" to id,
                "title" to title,
                "workspace" to workspace,
                "agentId" to agentId,
                "endpointId" to endpointId,
                "model" to model,
            ),
        )
        return getSession(id) ?: Session(id = id, title = title, workspace = workspace)
    }

    suspend fun listSessions(limit: Int = 200, includeArchived: Boolean = false): List<Session> =
        call("session.list", jsonOf("limit" to limit, "includeArchived" to includeArchived))
            .objects("sessions")
            .map { it.toSession() }

    suspend fun getSession(id: String): Session? = runCatching {
        call("session.get", jsonOf("id" to id)).toSession()
    }.getOrNull()

    suspend fun updateSession(
        id: String,
        title: String? = null,
        workspace: String? = null,
        agentId: String? = null,
        endpointId: String? = null,
        model: String? = null,
        archived: Boolean? = null,
    ) {
        call(
            "session.update",
            jsonOf(
                "id" to id,
                "title" to title,
                "workspace" to workspace,
                "agentId" to agentId,
                "endpointId" to endpointId,
                "model" to model,
                "archived" to archived,
            ),
        )
    }

    /** A compaction's digest as stored: where it folded the history, and the summary it wrote. */
    data class StoredDigest(
        val id: String,
        val version: Int,
        val createdAt: Long,
        val fromSeq: Long,
        /** The last message it archived: its seam goes after this one. */
        val toSeq: Long,
        /** What it costs in a request. */
        val tokens: Int,
        /** Null when the body did not decode, or a build before bodies were listed. */
        val digest: ConversationDigest?,
    )

    /** Every digest of [sessionId], oldest first, with its summary. */
    suspend fun digests(sessionId: String): List<StoredDigest> =
        call("digest.list", jsonOf("sessionId" to sessionId)).objects("digests").map {
            StoredDigest(
                id = it.str("id", ""),
                version = it.int("version"),
                createdAt = it.long("createdAt"),
                fromSeq = it.long("fromSeq"),
                toSeq = it.long("toSeq"),
                tokens = it.int("tokens"),
                digest = it.obj("body")?.let { body ->
                    runCatching { json.decodeFromJsonElement(ConversationDigest.serializer(), body) }.getOrNull()
                },
            )
        }

    suspend fun deleteSession(id: String) {
        call("session.delete", jsonOf("id" to id), TaskClass.BACKGROUND)
    }

    suspend fun stats(sessionId: String): SessionStats =
        call("session.stats", jsonOf("id" to sessionId)).let {
            SessionStats(
                liveMessages = it.int("liveMessages"),
                archivedMessages = it.int("archivedMessages"),
                liveTokens = it.long("liveTokens"),
                archivedTokens = it.long("archivedTokens"),
                blobBytes = it.long("blobBytes"),
                digests = it.int("digests"),
                toolCalls = it.int("toolCalls"),
            )
        }

    private fun JsonObject.toSession() = Session(
        id = str("id", ""),
        title = str("title", ""),
        workspace = str("workspace", ""),
        agentId = str("agentId"),
        endpointId = str("endpointId"),
        model = str("model"),
        createdAt = long("createdAt"),
        updatedAt = long("updatedAt"),
        archived = bool("archived"),
        nextSeq = long("nextSeq"),
        liveTokens = long("liveTokens"),
        totalTokens = long("totalTokens"),
        compactions = int("compactions"),
        preview = str("preview", ""),
    )

    /* -------------------------------------------------------------- messages */

    /**
     * Append a message and return it with its assigned sequence number.
     *
     * The token count is computed here rather than by the caller so that every
     * write path agrees on it — the context meter is only trustworthy if
     * nothing bypasses this.
     */
    suspend fun append(message: Message): Message {
        val flattened = message.flatten()
        val tokens = if (message.tokens > 0) message.tokens else estimateTokens(message)
        val result = call(
            "message.append",
            jsonOf(
                "sessionId" to message.sessionId,
                "id" to message.id,
                "role" to message.role.wire(),
                "status" to message.status.wire(),
                "content" to encodeBlocks(message.content),
                "text" to flattened,
                "tokens" to tokens,
                "pinned" to message.pinned,
            ),
            // On the turn's critical path: a tool result waits on it, and the
            // background pool's threads are the ones Android throttles.
            TaskClass.INTERACTIVE,
        )
        return message.copy(
            seq = result.long("seq"),
            createdAt = result.long("createdAt"),
            tokens = tokens,
        )
    }

    /**
     * Update a message in place. Used heavily while streaming: the assistant
     * message is appended empty, then rewritten as deltas arrive.
     *
     * Token recount is skipped unless the content changed, because a status-only
     * update happens on every single chunk.
     *
     * [index] false keeps the searchable text as it was: the periodic saves of
     * a streaming message pass it, since each re-index rewrote the whole
     * message into the full-text index, growing with every save, while the
     * stream waited on it. The final save indexes once.
     */
    suspend fun update(
        id: String,
        content: List<Block>? = null,
        status: MessageStatus? = null,
        recountTokens: Boolean = content != null,
        index: Boolean = true,
    ) {
        val edited = content?.let { blocks ->
            Message(
                id = id,
                sessionId = "",
                role = Role.ASSISTANT,
                content = blocks,
            )
        }
        call(
            "message.update",
            jsonOf(
                "id" to id,
                "content" to content?.let { encodeBlocks(it) },
                "text" to edited?.takeIf { index }?.flatten(),
                "status" to status?.wire(),
                "tokens" to edited?.takeIf { recountTokens }?.let(::estimateTokens),
            ),
            // The stream waits on this; see append.
            TaskClass.INTERACTIVE,
        )
    }

    /**
     * Set a message's token count, and its session's with it. For counts
     * stored by an earlier build, which counted each tool result by its
     * 400-character excerpt; see [Message.promptText].
     */
    suspend fun recount(id: String, tokens: Int) {
        call("message.update", jsonOf("id" to id, "tokens" to tokens), TaskClass.BACKGROUND)
    }

    /** What [message] costs in a request: its text whole, its framing, its images by area. */
    fun estimateTokens(message: Message): Int =
        NativeCore.estimateTokens(message.promptText()) + TOKENS_PER_MESSAGE +
            message.content.filterIsInstance<Block.Image>().sumOf { it.estimatedTokens() }

    /**
     * The live window, oldest first. Archived messages are excluded.
     *
     * Whole by default. It used to stop at the newest 400 messages, and the
     * agent's at 600: past that, the oldest live turns were left out of the
     * request and off the screen without a word, and a window cut between a
     * call and its result was a request the provider refused. What bounds
     * the window is compaction, by tokens, not a count of messages.
     */
    suspend fun window(
        sessionId: String,
        limit: Int = Int.MAX_VALUE,
        includeArchived: Boolean = false,
    ): List<Message> = call(
        "message.window",
        jsonOf("sessionId" to sessionId, "limit" to limit, "includeArchived" to includeArchived),
    ).objects("messages").map { it.toMessage(sessionId) }

    /** An explicit sequence range, including archived messages. */
    suspend fun range(sessionId: String, fromSeq: Long, toSeq: Long): List<Message> = call(
        "message.range",
        jsonOf("sessionId" to sessionId, "fromSeq" to fromSeq, "toSeq" to toSeq),
    ).objects("messages").map { it.toMessage(sessionId) }

    data class ArchiveResult(val archived: Int, val tokensFreed: Long)

    /**
     * Mark a span as archived.
     *
     * Nothing is deleted — the rows stay, stay indexed for FTS, and stay
     * readable through [range]. "Archived" only means "not in the next
     * request". That distinction is what makes `conversation_search` work after
     * a compaction.
     */
    suspend fun archive(
        sessionId: String,
        fromSeq: Long,
        toSeq: Long,
        digestId: String?,
        keepPinned: Boolean = true,
    ): ArchiveResult = call(
        "message.archive",
        jsonOf(
            "sessionId" to sessionId,
            "fromSeq" to fromSeq,
            "toSeq" to toSeq,
            "digestId" to digestId,
            "keepPinned" to keepPinned,
        ),
        TaskClass.BACKGROUND,
    ).let { ArchiveResult(it.int("archived"), it.long("tokensFreed")) }

    suspend fun setPinned(messageId: String, pinned: Boolean) {
        call("message.pin", jsonOf("id" to messageId, "pinned" to pinned))
    }

    data class RewindResult(
        /** Messages deleted: the checkpoint's own and every later one. */
        val messages: Int,
        /** Messages a later compaction had archived, live again. */
        val restored: Int,
        /** Digests written since the checkpoint, deleted. */
        val digests: Int,
        val liveTokens: Long,
    )

    /**
     * Take the session back to the moment [messageId] was sent: a checkpoint.
     *
     * The message and everything after it are deleted, with their tool calls
     * and spilled output, and so is every digest written since — the messages
     * those had archived are live again, so the next request carries what it
     * would have carried then. Snapshots taken since go too, except
     * [keepSnapshots]: the changes whose files could not be put back, which
     * stay listed so the user can see them.
     *
     * Files are the caller's part, done first: see [ChangeLedger.sinceCheckpoint].
     */
    suspend fun rewind(
        sessionId: String,
        messageId: String,
        keepSnapshots: Collection<String> = emptyList(),
    ): RewindResult = call(
        "message.rewind",
        jsonOf("sessionId" to sessionId, "messageId" to messageId, "keepSnapshots" to keepSnapshots),
    ).let {
        RewindResult(
            messages = it.int("messages"),
            restored = it.int("restored"),
            digests = it.int("digests"),
            liveTokens = it.long("liveTokens"),
        )
    }

    private fun JsonObject.toMessage(sessionId: String) = Message(
        id = str("id", ""),
        sessionId = sessionId,
        seq = long("seq"),
        role = roleFromWire(str("role", "user")),
        status = messageStatusFromWire(str("status", "complete")),
        createdAt = long("createdAt"),
        content = decodeBlocks(this["content"]),
        tokens = int("tokens"),
        pinned = bool("pinned"),
        archived = bool("archived"),
    )

    private fun encodeBlocks(blocks: List<Block>) =
        json.encodeToJsonElement(blockListSerializer, blocks)

    private fun decodeBlocks(element: kotlinx.serialization.json.JsonElement?): List<Block> {
        val array = element as? JsonArray ?: return emptyList()
        return runCatching { json.decodeFromJsonElement(blockListSerializer, array) }
            .getOrElse { error ->
                // A schema change should degrade to showing the text, not to an
                // unopenable session.
                Log.w(TAG, "block decode failed: ${error.message}")
                listOf(Block.Text("[unreadable message content]"))
            }
    }

    /* ------------------------------------------------------------ tool calls */

    suspend fun recordToolCall(record: ToolCallRecord) {
        call(
            "tool.record",
            jsonOf(
                "id" to record.id,
                "sessionId" to record.sessionId,
                "messageId" to record.messageId,
                "name" to record.name,
                "args" to runCatching {
                    json.parseToJsonElement(record.argumentsJson)
                }.getOrElse { jsonOf() },
                "status" to record.status.wire(),
            ),
            TaskClass.BACKGROUND,
        )
    }

    suspend fun updateToolCall(
        id: String,
        status: ToolCallStatus? = null,
        exitCode: Int? = null,
        preview: String? = null,
        blobId: String? = null,
        tokens: Int? = null,
        ended: Boolean = false,
    ) {
        call(
            "tool.update",
            jsonOf(
                "id" to id,
                "status" to status?.wire(),
                "endedAt" to if (ended) System.currentTimeMillis() else null,
                "exitCode" to exitCode,
                "preview" to preview,
                "blobId" to blobId,
                "tokens" to tokens,
            ),
            TaskClass.BACKGROUND,
        )
    }

    suspend fun toolCalls(sessionId: String): List<ToolCallRecord> =
        call("tool.list", jsonOf("sessionId" to sessionId)).objects("toolCalls").map {
            ToolCallRecord(
                id = it.str("id", ""),
                sessionId = sessionId,
                messageId = it.str("messageId", ""),
                name = it.str("name", ""),
                argumentsJson = it.obj("args")?.toString() ?: "{}",
                status = toolCallStatusFromWire(it.str("status", "pending")),
                startedAt = it.long("startedAt").takeIf { v -> v > 0 },
                endedAt = it.long("endedAt").takeIf { v -> v > 0 },
                exitCode = it.intOrNull("exitCode"),
                preview = it.str("preview", ""),
                blobId = it.str("blobId"),
                tokens = it.int("tokens"),
            )
        }

    /* ----------------------------------------------------------------- blobs */

    /**
     * Spill a large payload out of the conversation.
     *
     * Tool output that would blow the per-result token cap goes here, and the
     * message keeps a head/tail excerpt plus this id. The model reads the rest
     * on demand, which turns an unbounded cost into an opt-in one.
     */
    suspend fun putBlob(
        data: String,
        kind: String = "tool_output",
        label: String = "",
        sessionId: String? = null,
        id: String = newId("b"),
    ): String {
        call(
            "blob.put",
            jsonOf(
                "id" to id,
                "data" to data,
                "kind" to kind,
                "label" to label,
                "sessionId" to sessionId,
            ),
            TaskClass.BACKGROUND,
        )
        return id
    }

    data class BlobPage(
        val id: String,
        val offset: Int,
        val size: Long,
        val eof: Boolean,
        val text: String,
    )

    suspend fun readBlob(id: String, offset: Int = 0, length: Int = 64 * 1024): BlobPage =
        call("blob.read", jsonOf("id" to id, "offset" to offset, "length" to length)).let {
            BlobPage(
                id = it.str("id", id),
                offset = it.int("offset"),
                size = it.long("size"),
                eof = it.bool("eof"),
                text = it.str("text", ""),
            )
        }

    suspend fun pruneBlobs(keepBytes: Long = 256L * 1024 * 1024) {
        runCatching {
            call("maintenance.prune_blobs", jsonOf("keepBytes" to keepBytes), TaskClass.BULK)
        }
    }

    /* --------------------------------------------------------------- digests */

    suspend fun saveDigest(sessionId: String, digest: ConversationDigest): String {
        val id = newId("d")
        call(
            "digest.save",
            jsonOf(
                "id" to id,
                "sessionId" to sessionId,
                "fromSeq" to digest.coversFromSeq,
                "toSeq" to digest.coversToSeq,
                "model" to digest.model,
                "tokens" to NativeCore.estimateTokens(digest.render()),
                "body" to json.encodeToJsonElement(ConversationDigest.serializer(), digest),
            ),
            TaskClass.BACKGROUND,
        )
        return id
    }

    suspend fun latestDigest(sessionId: String): ConversationDigest? {
        val result = call("digest.latest", jsonOf("sessionId" to sessionId))
        val body = result.obj("body") ?: return null
        return runCatching {
            json.decodeFromJsonElement(ConversationDigest.serializer(), body)
        }.getOrNull()
    }

    /* ---------------------------------------------------------------- search */

    /**
     * Full-text search across the conversation.
     *
     * Backs both the sessions-screen search box and the `conversation_search`
     * tool, which is the mechanism that lets a compacted agent recover a detail
     * it no longer has in context.
     */
    suspend fun search(
        query: String,
        sessionId: String? = null,
        limit: Int = 20,
        archivedOnly: Boolean = false,
    ): List<MessageHit> = call(
        "search.messages",
        jsonOf(
            "query" to query,
            "sessionId" to sessionId,
            "limit" to limit,
            "archivedOnly" to archivedOnly,
        ),
    ).objects("hits").map {
        MessageHit(
            messageId = it.str("messageId", ""),
            sessionId = it.str("sessionId", ""),
            seq = it.long("seq"),
            role = it.str("role", ""),
            createdAt = it.long("createdAt"),
            archived = it.bool("archived"),
            snippet = it.str("snippet", ""),
            score = it.double("score"),
        )
    }

    /* ---------------------------------------------------------------- memory */

    suspend fun memoryGet(scope: String, key: String): String? =
        call("memory.get", jsonOf("scope" to scope, "key" to key)).str("value")

    suspend fun memorySet(scope: String, key: String, value: String) {
        call("memory.set", jsonOf("scope" to scope, "key" to key, "value" to value), TaskClass.BACKGROUND)
    }

    suspend fun memoryList(scope: String): Map<String, String> =
        call("memory.list", jsonOf("scope" to scope)).objects("entries")
            .associate { it.str("key", "") to it.str("value", "") }
            .filterKeys { it.isNotEmpty() }

    suspend fun memoryDelete(scope: String, key: String) {
        call("memory.delete", jsonOf("scope" to scope, "key" to key), TaskClass.BACKGROUND)
    }

    /* ------------------------------------------------------------- snapshots */

    /**
     * Record a file's before/after state around an agent edit, so any change
     * can be reverted from the UI even after the session is reopened.
     */
    suspend fun recordSnapshot(
        sessionId: String,
        path: String,
        beforeBlobId: String?,
        afterBlobId: String?,
        toolCallId: String? = null,
    ): String {
        val id = newId("snap")
        call(
            "snapshot.put",
            jsonOf(
                "id" to id,
                "sessionId" to sessionId,
                "path" to path,
                "beforeBlob" to beforeBlobId,
                "afterBlob" to afterBlobId,
                "toolCallId" to toolCallId,
            ),
            TaskClass.BACKGROUND,
        )
        return id
    }

    data class Snapshot(
        val id: String,
        val path: String,
        val beforeBlobId: String?,
        val afterBlobId: String?,
        val createdAt: Long,
        val reverted: Boolean,
        val toolCallId: String?,
    )

    suspend fun snapshots(sessionId: String): List<Snapshot> =
        call("snapshot.list", jsonOf("sessionId" to sessionId)).objects("snapshots").map {
            Snapshot(
                id = it.str("id", ""),
                path = it.str("path", ""),
                beforeBlobId = it.str("beforeBlob"),
                afterBlobId = it.str("afterBlob"),
                createdAt = it.long("createdAt"),
                reverted = it.bool("reverted"),
                toolCallId = it.str("toolCallId"),
            )
        }

    suspend fun markReverted(snapshotId: String) {
        call("snapshot.mark_reverted", jsonOf("id" to snapshotId), TaskClass.BACKGROUND)
    }

    companion object {
        private const val TAG = "ConversationStore"

        /** Wire-format framing per message, mirroring `tokens.rs`. */
        const val TOKENS_PER_MESSAGE = 4

        fun newId(prefix: String): String =
            "$prefix-" + UUID.randomUUID().toString().replace("-", "").take(16)
    }
}

/* ---- wire-name helpers --------------------------------------------------
 *
 * The native store speaks lowercase strings. These convert both ways.
 * Deliberately hand-written rather than going through the `@SerialName`
 * metadata: the mapping is three lines, and reaching into a serializer
 * descriptor to read a name is slower and less obvious.
 */

internal fun Role.wire(): String = when (this) {
    Role.SYSTEM -> "system"
    Role.USER -> "user"
    Role.ASSISTANT -> "assistant"
    Role.TOOL -> "tool"
    Role.DIGEST -> "digest"
}

internal fun roleFromWire(value: String): Role = when (value) {
    "system" -> Role.SYSTEM
    "assistant" -> Role.ASSISTANT
    "tool" -> Role.TOOL
    "digest" -> Role.DIGEST
    else -> Role.USER
}

internal fun MessageStatus.wire(): String = when (this) {
    MessageStatus.STREAMING -> "streaming"
    MessageStatus.COMPLETE -> "complete"
    MessageStatus.ERROR -> "error"
    MessageStatus.ABORTED -> "aborted"
}

internal fun messageStatusFromWire(value: String): MessageStatus = when (value) {
    "streaming" -> MessageStatus.STREAMING
    "error" -> MessageStatus.ERROR
    "aborted" -> MessageStatus.ABORTED
    else -> MessageStatus.COMPLETE
}

internal fun ToolCallStatus.wire(): String = name.lowercase()

internal fun toolCallStatusFromWire(value: String): ToolCallStatus =
    ToolCallStatus.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: ToolCallStatus.PENDING
