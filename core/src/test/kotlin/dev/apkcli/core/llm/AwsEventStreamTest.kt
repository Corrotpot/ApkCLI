package dev.apkcli.core.llm

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AwsEventStreamTest {

    private fun frame(eventType: String, payload: String) = AwsEventStream.encode(
        mapOf(":message-type" to "event", ":event-type" to eventType, ":content-type" to "application/json"),
        payload.toByteArray(),
    )

    @Test
    fun `frames read back in order with their headers and payloads`() {
        val source = Buffer()
            .write(frame("assistantResponseEvent", """{"content":"Hel"}"""))
            .write(frame("assistantResponseEvent", """{"content":"lo"}"""))

        val first = AwsEventStream.read(source)!!
        assertEquals("event", first.messageType)
        assertEquals("assistantResponseEvent", first.eventType)
        assertEquals("""{"content":"Hel"}""", first.payloadText)
        assertEquals("""{"content":"lo"}""", AwsEventStream.read(source)!!.payloadText)
        assertNull("a clean end is no frame, not an error", AwsEventStream.read(source))
    }

    @Test
    fun `a damaged payload fails the message checksum`() {
        val bytes = frame("assistantResponseEvent", """{"content":"x"}""")
        bytes[bytes.size - 6] = (bytes[bytes.size - 6] + 1).toByte()
        val error = assertThrows(AwsEventStream.FormatException::class.java) { AwsEventStream.decode(bytes) }
        assertEquals("event-stream message checksum mismatch", error.message)
    }

    @Test
    fun `a damaged length fails the prelude checksum before anything is allocated`() {
        val bytes = frame("assistantResponseEvent", "{}")
        bytes[1] = 0x7f
        assertThrows(AwsEventStream.FormatException::class.java) { AwsEventStream.read(Buffer().write(bytes)) }
    }

    @Test
    fun `non-string header types are decoded`() {
        // ":flag" true (type 0), "n" int32 (type 4) = 258, then a string.
        val headers = byteArrayOf(
            5, ':'.code.toByte(), 'f'.code.toByte(), 'l'.code.toByte(), 'a'.code.toByte(), 'g'.code.toByte(), 0,
            1, 'n'.code.toByte(), 4, 0, 0, 1, 2,
        )
        val payload = "{}".toByteArray()
        val total = 16 + headers.size + payload.size
        val buffer = java.nio.ByteBuffer.allocate(total).putInt(total).putInt(headers.size)
        val array = buffer.array()
        buffer.putInt(java.util.zip.CRC32().apply { update(array, 0, 8) }.value.toInt())
        buffer.put(headers).put(payload)
        buffer.putInt(java.util.zip.CRC32().apply { update(array, 0, total - 4) }.value.toInt())

        val decoded = AwsEventStream.decode(array)
        assertEquals(true, decoded.headers[":flag"])
        assertEquals(258, decoded.headers["n"])
    }
}
