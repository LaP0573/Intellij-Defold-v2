package com.aridclown.intellij.defold.logging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets.UTF_8

class RemoteryFrameDecoderTest {
    @Test
    fun `decodes a single LOGM frame into its UTF-8 payload`() {
        val decoder = RemoteryFrameDecoder()

        decoder.feed(logmFrame("INFO:GAME: ready"))
        val frames = decoder.drain()

        assertThat(frames)
            .singleElement()
            .isInstanceOf(RemoteryFrame.LogMessage::class.java)
            .extracting("text")
            .isEqualTo("INFO:GAME: ready")
    }

    @Test
    fun `extracts every LOGM payload from a multi-frame buffer`() {
        val decoder = RemoteryFrameDecoder()
        val payloads = listOf("DEBUG: spawn", "WARNING: low fps", "ERROR: missing texture")

        decoder.feed(payloads.flatMap { logmFrame(it).toList() }.toByteArray())
        val frames = decoder.drain()

        assertThat(frames)
            .extracting("text")
            .containsExactlyElementsOf(payloads)
    }

    @Test
    fun `holds back a partial frame until the rest of the bytes arrive`() {
        val decoder = RemoteryFrameDecoder()
        val full = logmFrame("split across feeds")
        val splitAt = full.size - 5

        decoder.feed(full.copyOfRange(0, splitAt))
        assertThat(decoder.drain()).isEmpty()

        decoder.feed(full.copyOfRange(splitAt, full.size))
        val frames = decoder.drain()

        assertThat(frames)
            .singleElement()
            .extracting("text")
            .isEqualTo("split across feeds")
        assertThat(decoder.isEmpty()).isTrue()
    }

    @Test
    fun `non-LOGM frames surface as Unknown without losing the trailing LOGM`() {
        val decoder = RemoteryFrameDecoder()

        decoder.feed(rawFrame("SMPL", byteArrayOf(1, 2, 3, 4)))
        decoder.feed(logmFrame("after sampler"))
        val frames = decoder.drain()

        assertThat(frames).hasSize(2)
        assertThat(frames[0]).isInstanceOf(RemoteryFrame.Unknown::class.java)
        assertThat((frames[0] as RemoteryFrame.Unknown).id).isEqualTo("SMPL")
        assertThat((frames[0] as RemoteryFrame.Unknown).payload).containsExactly(1, 2, 3, 4)
        assertThat((frames[1] as RemoteryFrame.LogMessage).text).isEqualTo("after sampler")
    }

    @Test
    fun `decodes multi-byte UTF-8 characters in the LOGM payload`() {
        val decoder = RemoteryFrameDecoder()
        val payload = "héllo — 日本語 🎮"

        decoder.feed(logmFrame(payload))
        val frames = decoder.drain()

        assertThat((frames.single() as RemoteryFrame.LogMessage).text).isEqualTo(payload)
    }

    @Test
    fun `flags an inner length that overruns the outer payload as Malformed`() {
        val decoder = RemoteryFrameDecoder()
        val payload = encodeUInt32(999) + ByteArray(4) { 0x41 }
        decoder.feed(rawFrame("LOGM", payload))

        val frames = decoder.drain()

        assertThat(frames)
            .singleElement()
            .isInstanceOf(RemoteryFrame.Malformed::class.java)
            .extracting("id")
            .isEqualTo("LOGM")
    }

    private fun logmFrame(text: String): ByteArray {
        val textBytes = text.toByteArray(UTF_8)
        val payload = encodeUInt32(textBytes.size) + textBytes
        return rawFrame("LOGM", payload)
    }

    private fun rawFrame(
        id: String,
        payload: ByteArray
    ): ByteArray {
        require(id.length == 4) { "frame id must be 4 ASCII chars" }
        return id.toByteArray(UTF_8) + encodeUInt32(payload.size) + payload
    }

    private fun encodeUInt32(value: Int): ByteArray = ByteBuffer
        .allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(value)
        .array()
}
