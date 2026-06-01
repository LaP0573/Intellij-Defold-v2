package com.aridclown.intellij.defold.hotreload

import com.aridclown.intellij.defold.EngineEndpoint
import com.aridclown.intellij.defold.hotreload.HotReloadService.Companion.RELOAD_CONTENT_TYPE
import com.aridclown.intellij.defold.hotreload.HotReloadService.Companion.RELOAD_ENDPOINT
import com.aridclown.intellij.defold.hotreload.HotReloadService.Companion.sendResourceReloadRequest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * Engine-route contract test for hot reload.
 *
 * Exercises the real networked send (`HotReloadService.sendResourceReloadRequest`) against an
 * okhttp `MockWebServer` — NOT the test-double dependencies. This pins the wire contract the
 * plugin shares with a running Defold engine: the request path, the `application/x-protobuf`
 * content type, and the decoded `Resource.Reload` body, plus non-2xx error surfacing.
 */
class HotReloadServiceEngineRouteTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `posts Resource Reload payload to the engine reload endpoint with x-protobuf content type`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("OK"))

        val payload = encodeReloadPayload(listOf("/main/player.scriptc", "/utils/helper.luac"))

        sendResourceReloadRequest(endpointFor(server), payload)

        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.path).isEqualTo(RELOAD_ENDPOINT)
        assertThat(recorded.getHeader("Content-Type")).isEqualTo(RELOAD_CONTENT_TYPE)

        val body = recorded.body.readByteArray()
        assertThat(body).isEqualTo(payload)
        assertThat(decodeReloadPayload(body)).containsExactly(
            "/main/player.scriptc",
            "/utils/helper.luac"
        )
    }

    @Test
    fun `surfaces engine non-2xx response as IOException`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        assertThatThrownBy {
            sendResourceReloadRequest(endpointFor(server), encodeReloadPayload(listOf("/main/player.scriptc")))
        }.isInstanceOf(IOException::class.java)
            .hasMessageContaining("Defold engine")
            .hasRootCauseInstanceOf(IOException::class.java)
            .hasRootCauseMessage("Engine reload request failed with status 500")
    }

    @Test
    fun `surfaces unreachable engine as IOException`() {
        val unreachable = server.url("/").let { url -> EngineEndpoint(url.host, url.port, null, 0L) }
        server.shutdown() // close the only listening socket

        assertThatThrownBy {
            sendResourceReloadRequest(unreachable, encodeReloadPayload(listOf("/main/player.scriptc")))
        }.isInstanceOf(IOException::class.java)
            .hasMessageContaining("Defold engine")
    }

    private fun endpointFor(server: MockWebServer): EngineEndpoint {
        val url = server.url("/")
        return EngineEndpoint(url.host, url.port, null, 0L)
    }

    /**
     * Hand-rolled `Resource.Reload { repeated string resources = 1 }` encoder for the test
     * fixture — independent from production's [HotReloadService.createProtobufReloadPayload] so
     * the test is not asserting "production agrees with itself".
     */
    private fun encodeReloadPayload(paths: List<String>): ByteArray = buildList {
        paths.forEach { path ->
            val bytes = path.toByteArray(Charsets.UTF_8)
            add(0x0A.toByte()) // field 1 (resources), wire type 2 (length-delimited)
            encodeVarint(bytes.size)
            addAll(bytes.toList())
        }
    }.toByteArray()

    private fun MutableList<Byte>.encodeVarint(value: Int) {
        var v = value
        while (v >= 0x80) {
            add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        add((v and 0x7F).toByte())
    }

    private fun decodeReloadPayload(payload: ByteArray): List<String> {
        val paths = mutableListOf<String>()
        var cursor = 0
        while (cursor < payload.size) {
            val tag = payload[cursor].toInt() and 0xFF
            assertThat(tag).isEqualTo(0x0A)
            cursor++

            val (length, next) = readVarint(payload, cursor)
            cursor = next
            paths += String(payload, cursor, length, Charsets.UTF_8)
            cursor += length
        }
        return paths
    }

    private fun readVarint(data: ByteArray, startIndex: Int): Pair<Int, Int> {
        var value = 0
        var shift = 0
        var index = startIndex
        while (true) {
            val byte = data[index].toInt() and 0xFF
            index++
            value = value or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
        }
        return value to index
    }
}
