package com.aridclown.intellij.defold.logging

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Decoder for Remotery's binary WebSocket frame protocol used by Defold engines.
 *
 * Wire format (little-endian, matching Remotery's x86/ARM-native layout):
 *
 *     | 4 ASCII chars : message id | uint32 : payload byte length | payload |
 *
 * For the `LOGM` message used by Defold's runtime logger, the payload itself is a
 * length-prefixed UTF-8 string:
 *
 *     | uint32 : text byte length | text bytes (UTF-8) |
 *
 * The decoder is byte-stream oriented: append bytes as they arrive on the wire
 * via [feed] and call [drain] to pull every complete frame. Bytes that don't yet
 * make up a complete frame stay buffered until the next [feed] call.
 */
internal class RemoteryFrameDecoder {
    private var buffer: ByteArray = ByteArray(0)

    /** Append raw bytes received from the WebSocket. */
    fun feed(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        buffer = buffer + bytes
    }

    /** Pull every complete frame currently buffered; partial bytes stay for the next [feed]. */
    fun drain(): List<RemoteryFrame> {
        val frames = mutableListOf<RemoteryFrame>()
        var offset = 0

        while (true) {
            if (buffer.size - offset < HEADER_SIZE) break

            val id = String(buffer, offset, ID_SIZE, UTF_8)
            val payloadLength = readUInt32(buffer, offset + ID_SIZE)
            val frameEnd = offset + HEADER_SIZE + payloadLength

            if (payloadLength < 0 || frameEnd > buffer.size) break

            val payload = buffer.copyOfRange(offset + HEADER_SIZE, frameEnd)
            frames += parse(id, payload)
            offset = frameEnd
        }

        if (offset > 0) {
            buffer = if (offset == buffer.size) ByteArray(0) else buffer.copyOfRange(offset, buffer.size)
        }

        return frames
    }

    /** True when nothing is held over for a future [feed]. */
    fun isEmpty(): Boolean = buffer.isEmpty()

    private fun parse(
        id: String,
        payload: ByteArray
    ): RemoteryFrame = when (id) {
        LOGM_ID -> parseLogMessage(payload)
        else -> RemoteryFrame.Unknown(id, payload)
    }

    private fun parseLogMessage(payload: ByteArray): RemoteryFrame {
        if (payload.size < LENGTH_PREFIX_SIZE) {
            return RemoteryFrame.Malformed(LOGM_ID, "LOGM payload truncated: ${payload.size} bytes")
        }
        val textLength = readUInt32(payload, 0)
        if (textLength < 0 || LENGTH_PREFIX_SIZE + textLength > payload.size) {
            return RemoteryFrame.Malformed(LOGM_ID, "LOGM declared text length $textLength exceeds payload ${payload.size}")
        }
        val text = String(payload, LENGTH_PREFIX_SIZE, textLength, UTF_8)
        return RemoteryFrame.LogMessage(text)
    }

    private fun readUInt32(
        src: ByteArray,
        at: Int
    ): Int = ByteBuffer
        .wrap(src, at, LENGTH_PREFIX_SIZE)
        .order(ByteOrder.LITTLE_ENDIAN)
        .int

    companion object {
        const val LOGM_ID: String = "LOGM"
        const val ID_SIZE: Int = 4
        const val LENGTH_PREFIX_SIZE: Int = 4
        const val HEADER_SIZE: Int = ID_SIZE + LENGTH_PREFIX_SIZE
    }
}

internal sealed class RemoteryFrame {
    data class LogMessage(
        val text: String
    ) : RemoteryFrame()

    data class Unknown(
        val id: String,
        val payload: ByteArray
    ) : RemoteryFrame() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Unknown) return false
            return id == other.id && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = 31 * id.hashCode() + payload.contentHashCode()
    }

    data class Malformed(
        val id: String,
        val reason: String
    ) : RemoteryFrame()
}
