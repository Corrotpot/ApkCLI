package dev.apkcli.core.llm

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A Chat Completions stream says a call is whole as soon as the next one
 * starts, not only at the end: the loop runs reads while the rest is written.
 */
class ToolCallEndTest {

    private fun stream(vararg frames: String): List<StreamEvent> {
        val body = frames.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
        val response = Response.Builder()
            .request(Request.Builder().url("https://example.invalid/v1/chat/completions").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("text/event-stream".toMediaType()))
            .build()
        val events = mutableListOf<StreamEvent>()
        OpenAiChatAdapter().consumeStream(response) { events += it }
        return events
    }

    /** One streamed fragment of a call: its id and name come with the first only. */
    private fun call(index: Int, id: String?, name: String?, arguments: String): String = buildJsonObject {
        putJsonArray("choices") {
            addJsonObject {
                putJsonObject("delta") {
                    putJsonArray("tool_calls") {
                        addJsonObject {
                            put("index", index)
                            if (id != null) put("id", id)
                            putJsonObject("function") {
                                if (name != null) put("name", name)
                                put("arguments", arguments)
                            }
                        }
                    }
                }
            }
        }
    }.toString()

    @Test
    fun `the first call ends when the second begins, and the second at the end`() {
        val events = stream(
            call(0, "a", "fs_read", """{"path":"""),
            call(0, null, null, """"x.kt"}"""),
            call(1, "b", "grep", """{"pattern":"y"}"""),
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
        )
        val calls = events.filter { it is StreamEvent.ToolCallStart || it is StreamEvent.ToolCallEnd }
        assertEquals(
            listOf(
                StreamEvent.ToolCallStart("a", "fs_read"),
                StreamEvent.ToolCallEnd("a"),
                StreamEvent.ToolCallStart("b", "grep"),
                StreamEvent.ToolCallEnd("b"),
            ),
            calls,
        )
        assertEquals(1, events.count { it == StreamEvent.ToolCallEnd("a") })
    }
}
