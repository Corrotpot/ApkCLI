package dev.apkcli.core.llm

import okio.BufferedSource
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * The AWS event-stream encoding (`application/vnd.amazon.eventstream`), the
 * binary framing Amazon's streaming APIs answer in instead of SSE.
 *
 * One frame:
 *
 *     total length   u32, big-endian, the whole frame
 *     headers length u32
 *     prelude CRC    u32, CRC-32 of the eight bytes above
 *     headers        name length u8, name, value type u8, value
 *     payload        here, one JSON object
 *     message CRC    u32, CRC-32 of everything before it
 *
 * Both checksums are verified: a frame that fails one is not trusted, since
 * a silently damaged frame becomes a silently damaged answer.
 */
object AwsEventStream {

    /** Generous bounds that still stop a corrupt length from allocating the heap away. */
    const val MAX_FRAME_BYTES = 16 * 1024 * 1024
    const val MAX_HEADERS_BYTES = 128 * 1024

    class Frame(val headers: Map<String, Any>, val payload: ByteArray) {
        fun header(name: String): String? = headers[name] as? String

        /** `event`, `error` or `exception`. */
        val messageType: String? get() = header(":message-type")

        /** For events: `assistantResponseEvent`, `toolUseEvent`… */
        val eventType: String? get() = header(":event-type")

        /** For exceptions: `ThrottlingException`, `ValidationException`… */
        val exceptionType: String? get() = header(":exception-type")

        val payloadText: String get() = payload.toString(Charsets.UTF_8)
    }

    class FormatException(message: String) : IOException(message)

    /** The next frame from [source], or null at a clean end of the stream. */
    fun read(source: BufferedSource): Frame? {
        if (source.exhausted()) return null
        val prelude = source.readByteArray(PRELUDE_BYTES.toLong())
        val view = ByteBuffer.wrap(prelude)
        val total = view.getInt(0).toLong() and 0xffffffffL
        val headersLength = view.getInt(4).toLong() and 0xffffffffL
        val preludeCrc = view.getInt(8).toLong() and 0xffffffffL
        if (crc(prelude, 0, 8) != preludeCrc) throw FormatException("event-stream prelude checksum mismatch")
        if (total < MIN_FRAME_BYTES || total > MAX_FRAME_BYTES ||
            headersLength > MAX_HEADERS_BYTES || headersLength > total - MIN_FRAME_BYTES
        ) {
            throw FormatException("event-stream frame bounds are invalid ($total, $headersLength)")
        }
        val rest = source.readByteArray(total - PRELUDE_BYTES)
        val frame = ByteArray(total.toInt())
        System.arraycopy(prelude, 0, frame, 0, PRELUDE_BYTES)
        System.arraycopy(rest, 0, frame, PRELUDE_BYTES, rest.size)
        return decode(frame)
    }

    /** One complete frame, exactly its total length long. */
    fun decode(frame: ByteArray): Frame {
        if (frame.size < MIN_FRAME_BYTES) throw FormatException("event-stream frame is shorter than 16 bytes")
        val view = ByteBuffer.wrap(frame)
        val total = view.getInt(0).toLong() and 0xffffffffL
        val headersLength = (view.getInt(4).toLong() and 0xffffffffL).toInt()
        if (total != frame.size.toLong()) throw FormatException("event-stream frame length does not match its prelude")
        if ((view.getInt(8).toLong() and 0xffffffffL) != crc(frame, 0, 8)) {
            throw FormatException("event-stream prelude checksum mismatch")
        }
        val messageCrc = view.getInt(frame.size - 4).toLong() and 0xffffffffL
        if (messageCrc != crc(frame, 0, frame.size - 4)) throw FormatException("event-stream message checksum mismatch")
        if (headersLength > frame.size - MIN_FRAME_BYTES) throw FormatException("event-stream headers overrun the frame")

        val headers = LinkedHashMap<String, Any>()
        var offset = PRELUDE_BYTES
        val end = PRELUDE_BYTES + headersLength
        fun need(count: Int) {
            if (offset + count > end) throw FormatException("event-stream header exceeds its declared bounds")
        }
        while (offset < end) {
            need(1)
            val nameLength = frame[offset++].toInt() and 0xff
            need(nameLength + 1)
            val name = String(frame, offset, nameLength, Charsets.UTF_8)
            offset += nameLength
            val value: Any = when (val type = frame[offset++].toInt()) {
                0 -> true
                1 -> false
                2 -> { need(1); frame[offset].toInt().also { offset += 1 } }
                3 -> { need(2); view.getShort(offset).toInt().also { offset += 2 } }
                4 -> { need(4); view.getInt(offset).also { offset += 4 } }
                5, 8 -> { need(8); view.getLong(offset).also { offset += 8 } }
                6, 7 -> {
                    need(2)
                    val length = view.getShort(offset).toInt() and 0xffff
                    offset += 2
                    need(length)
                    val bytes = frame.copyOfRange(offset, offset + length)
                    offset += length
                    if (type == 7) String(bytes, Charsets.UTF_8) else bytes
                }
                9 -> { need(16); frame.copyOfRange(offset, offset + 16).also { offset += 16 } }
                else -> throw FormatException("event-stream header '$name' has unknown type $type")
            }
            headers[name] = value
        }
        return Frame(headers, frame.copyOfRange(end, frame.size - 4))
    }

    /** One frame with string headers, as a server writes it. For tests and fixtures. */
    fun encode(headers: Map<String, String>, payload: ByteArray): ByteArray {
        val headerBytes = java.io.ByteArrayOutputStream()
        for ((name, value) in headers) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val valueBytes = value.toByteArray(Charsets.UTF_8)
            headerBytes.write(nameBytes.size)
            headerBytes.write(nameBytes)
            headerBytes.write(7)
            headerBytes.write(valueBytes.size ushr 8)
            headerBytes.write(valueBytes.size and 0xff)
            headerBytes.write(valueBytes)
        }
        val headersArray = headerBytes.toByteArray()
        val total = MIN_FRAME_BYTES + headersArray.size + payload.size
        val buffer = ByteBuffer.allocate(total)
        buffer.putInt(total)
        buffer.putInt(headersArray.size)
        val frame = buffer.array()
        buffer.putInt(crc(frame, 0, 8).toInt())
        buffer.put(headersArray)
        buffer.put(payload)
        buffer.putInt(crc(frame, 0, total - 4).toInt())
        return frame
    }

    private fun crc(bytes: ByteArray, offset: Int, length: Int): Long =
        CRC32().apply { update(bytes, offset, length) }.value

    private const val PRELUDE_BYTES = 12
    private const val MIN_FRAME_BYTES = 16
}
