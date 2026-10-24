package dev.apkcli.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Conversation model.
 *
 * A message is a list of typed blocks rather than a string, because a single
 * assistant turn routinely contains reasoning, prose and several tool
 * invocations, and the UI has to render each differently while the wire format
 * has to reassemble them in order. Flattening to text loses the ordering that
 * makes "it thought, then it decided, then it acted" legible.
 */

@Serializable
enum class Role {
    @SerialName("system") SYSTEM,
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,

    /** Results handed back to the model. One message may carry several. */
    @SerialName("tool") TOOL,

    /**
     * A compaction summary standing in for archived turns. Modelled as its own
     * role so the UI can mark the seam and the prompt builder can place it
     * ahead of the live window.
     */
    @SerialName("digest") DIGEST,
}

@Serializable
enum class MessageStatus {
    @SerialName("streaming") STREAMING,
    @SerialName("complete") COMPLETE,
    @SerialName("error") ERROR,
    @SerialName("aborted") ABORTED,
}

@Serializable
sealed interface Block {

    @Serializable
    @SerialName("text")
    data class Text(val text: String) : Block

    /**
     * Reasoning output.
     *
     * [redacted] covers providers that return encrypted reasoning: there is
     * content to account for in the token budget and to echo back on the next
     * turn, but nothing displayable.
     *
     * [signature] carries the provider's opaque token (Anthropic's
     * `signature`, the OpenAI Responses `encrypted_content`). It must survive a
     * round trip or the provider rejects the follow-up request.
     */
    @Serializable
    @SerialName("thinking")
    data class Thinking(
        val text: String,
        val redacted: Boolean = false,
        val signature: String? = null,
        val tokens: Int = 0,
    ) : Block

    /** A tool invocation. The result arrives in a later [Role.TOOL] message. */
    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        val callId: String,
        val name: String,
        /** Raw JSON. Kept as a string because it streams in fragment by fragment. */
        val argumentsJson: String,
    ) : Block

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        val callId: String,
        val name: String,
        val content: String,
        val isError: Boolean = false,
        /**
         * Set when the full output was spilled to the blob store. The model can
         * page through it with `conversation_read`.
         */
        val blobId: String? = null,
        val truncated: Boolean = false,
        val durationMs: Long = 0,
        /**
         * The change an edit made, for the transcript. Kept with the result so
         * a reloaded session still shows the diff rather than the one-line
         * summary the model got. Never sent to the model: the adapters build
         * tool messages from [content] alone.
         */
        val diff: DiffSummary? = null,
        /** What a dispatched subagent did, for its card; like [diff], never sent to the model. */
        val subagent: SubagentRun? = null,
    ) : Block

    /** See [ToolResult.diff]. [unified] is capped; [truncated] says when it was cut. */
    @Serializable
    data class DiffSummary(
        val path: String,
        val unified: String,
        val added: Int,
        val removed: Int,
        val snapshotId: String? = null,
        val truncated: Boolean = false,
    )

    /** An image the user attached, or a screenshot a tool produced. */
    @Serializable
    @SerialName("image")
    data class Image(
        val mimeType: String,
        /** base64, without a data: prefix. */
        val data: String,
        val label: String? = null,
        /** Pixel size as sent, when known: what the providers bill an image by. */
        val width: Int? = null,
        val height: Int? = null,
    ) : Block {
        /**
         * What the image costs in the context window. Providers bill by area:
         * Anthropic about one token per 750 pixels up to ~1600, OpenAI 85 plus
         * 170 per 512-pixel tile. The first is the larger for the sizes the app
         * sends, so it is the estimate. Without a size, a full-size image.
         */
        fun estimatedTokens(): Int {
            val w = width ?: return FULL_IMAGE_TOKENS
            val h = height ?: return FULL_IMAGE_TOKENS
            return (w.toLong() * h / 750).toInt().coerceIn(MIN_IMAGE_TOKENS, FULL_IMAGE_TOKENS)
        }

        private companion object {
            const val MIN_IMAGE_TOKENS = 85
            const val FULL_IMAGE_TOKENS = 1_600
        }
    }

    /**
     * What the app says about a message, drawn under it: the reply stopped
     * at its output limit, the provider's filter stopped it, the request
     * failed and why. Kept with the message, so a reopened chat says it too.
     *
     * Never sent to the model, nor counted or indexed: the adapters build
     * requests from the other blocks alone. [kind] is a string, not an
     * enum, so a kind this build does not know still reads.
     */
    @Serializable
    @SerialName("notice")
    data class Notice(
        val kind: String,
        /** For [OUTPUT_LIMIT]: the output limit the request asked for, in tokens. */
        val limit: Int = 0,
        /** For [FAILED]: the error, as the provider or the loop put it. */
        val text: String? = null,
    ) : Block {
        companion object {
            /** The reply stopped where the output limit ([limit]) did: it may end mid-sentence or mid-call. */
            const val OUTPUT_LIMIT = "output_limit"

            /** The provider's content filter stopped the reply. */
            const val FILTERED = "filtered"

            /** The request failed; [text] says how. */
            const val FAILED = "failed"
        }
    }
}

@Serializable
data class Message(
    val id: String,
    val sessionId: String,
    val seq: Long = -1,
    val role: Role,
    val status: MessageStatus = MessageStatus.COMPLETE,
    val createdAt: Long = 0,
    val content: List<Block> = emptyList(),
    val tokens: Int = 0,
    /** Pinned messages survive compaction verbatim. */
    val pinned: Boolean = false,
    val archived: Boolean = false,
    /** Which digest replaced this message, if any. */
    val digestId: String? = null,
    /** Model that produced it; null for user messages. */
    val model: String? = null,
    val errorMessage: String? = null,
) {
    val text: String
        get() = content.filterIsInstance<Block.Text>().joinToString("\n") { it.text }

    val thinking: List<Block.Thinking>
        get() = content.filterIsInstance<Block.Thinking>()

    val toolUses: List<Block.ToolUse>
        get() = content.filterIsInstance<Block.ToolUse>()

    val toolResults: List<Block.ToolResult>
        get() = content.filterIsInstance<Block.ToolResult>()

    val notices: List<Block.Notice>
        get() = content.filterIsInstance<Block.Notice>()

    /**
     * What a request carries of this message, as text, for its token count:
     * [flatten], with each tool result whole. Counted from [flatten], a
     * 16 000-character build log cost the hundred tokens of its 400-character
     * excerpt, so a session of tool calls read as a fraction of its real size
     * and compaction came only after the provider refused the request.
     * Images are counted by area, separately.
     */
    fun promptText(): String = buildString {
        for (block in content) {
            when (block) {
                is Block.Text -> appendLine(block.text)
                is Block.Thinking -> if (!block.redacted) appendLine(block.text)
                is Block.ToolUse -> appendLine("${block.name} ${block.argumentsJson}")
                is Block.ToolResult -> appendLine("${block.name} -> ${block.content}")
                is Block.Image, is Block.Notice -> Unit
            }
        }
    }.trim()

    /**
     * Plain-text projection used for the FTS index and list previews.
     *
     * Reasoning is included: "why did it decide that" is one of the main things
     * worth searching for in a long session. Tool *arguments* are included but
     * tool *output* is not — output is usually machine noise and would swamp
     * the index.
     */
    fun flatten(): String = buildString {
        for (block in content) {
            when (block) {
                is Block.Text -> appendLine(block.text)
                is Block.Thinking -> if (!block.redacted) appendLine(block.text)
                is Block.ToolUse -> appendLine("${block.name} ${block.argumentsJson}")
                is Block.ToolResult -> appendLine("${block.name} -> ${block.content.take(400)}")
                is Block.Image -> appendLine("[image ${block.label ?: block.mimeType}]")
                is Block.Notice -> Unit
            }
        }
    }.trim()
}

@Serializable
data class Session(
    val id: String,
    val title: String = "",
    /** Absolute path of the project directory this session is bound to. */
    val workspace: String = "",
    val agentId: String? = null,
    val endpointId: String? = null,
    val model: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val archived: Boolean = false,
    val nextSeq: Long = 0,
    /** Tokens currently in the live window — what the next request will cost. */
    val liveTokens: Long = 0,
    /** Everything ever written, including archived turns. */
    val totalTokens: Long = 0,
    val compactions: Int = 0,
    val preview: String = "",
)

@Serializable
data class SessionStats(
    val liveMessages: Int = 0,
    val archivedMessages: Int = 0,
    val liveTokens: Long = 0,
    val archivedTokens: Long = 0,
    val blobBytes: Long = 0,
    val digests: Int = 0,
    val toolCalls: Int = 0,
)

@Serializable
enum class ToolCallStatus {
    /** Waiting on the user to approve. */
    @SerialName("pending") PENDING,
    @SerialName("approved") APPROVED,
    @SerialName("denied") DENIED,
    @SerialName("running") RUNNING,
    @SerialName("ok") OK,
    @SerialName("error") ERROR,
    @SerialName("aborted") ABORTED,
}

@Serializable
data class ToolCallRecord(
    val id: String,
    val sessionId: String,
    val messageId: String,
    val name: String,
    val argumentsJson: String = "{}",
    val status: ToolCallStatus = ToolCallStatus.PENDING,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val exitCode: Int? = null,
    val preview: String = "",
    val blobId: String? = null,
    val tokens: Int = 0,
) {
    val durationMs: Long?
        get() = if (startedAt != null && endedAt != null) endedAt - startedAt else null
}

/** A full-text hit in the conversation history, live or archived. */
@Serializable
data class MessageHit(
    val messageId: String,
    val sessionId: String,
    val seq: Long,
    val role: String,
    val createdAt: Long,
    val archived: Boolean,
    val snippet: String,
    val score: Double,
)
