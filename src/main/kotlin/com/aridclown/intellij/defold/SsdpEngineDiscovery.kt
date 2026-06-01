package com.aridclown.intellij.defold

import com.intellij.openapi.diagnostic.Logger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.time.Duration

/**
 * SSDP/UPnP-based discovery for already-running Defold engines on the local network.
 *
 * Ported from the Defold Buddy VSCode extension (`findRunningDefoldGame.ts`): sends an
 * M-SEARCH multicast and listens for short-lived UDP responses. Each response is a
 * tiny HTTP/1.1 header set whose `LOCATION` points at the engine's HTTP service.
 *
 * The implementation is best-effort and never throws — discovery failure simply yields
 * an empty list, so it composes cleanly with the in-memory direct-launch endpoints in
 * [EngineDiscoveryService.currentEndpoints].
 */
object SsdpEngineDiscovery {
    private val logger = Logger.getInstance(SsdpEngineDiscovery::class.java)

    private const val SSDP_HOST = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val SSDP_SEARCH_TARGET = "upnp:rootdevice"
    private const val DEFOLD_MARKER = "defold"
    private val DEFAULT_TIMEOUT = Duration.ofMillis(700)

    fun discover(timeout: Duration = DEFAULT_TIMEOUT): List<EngineEndpoint> {
        val timeoutMillis = timeout.toMillis()
        if (timeoutMillis <= 0) return emptyList()

        val payload = ssdpSearchPayload()
        val responses = mutableListOf<String>()

        return runCatching {
            val ssdpAddress = InetAddress.getByName(SSDP_HOST)
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMillis.toInt().coerceAtLeast(1)
                socket.send(DatagramPacket(payload, payload.size, ssdpAddress, SSDP_PORT))

                val deadline = System.currentTimeMillis() + timeoutMillis
                val buffer = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: Exception) {
                        // soTimeout exceeded or socket closed — done collecting responses.
                        break
                    }
                    val text = String(packet.data, 0, packet.length, Charsets.US_ASCII)
                    if (text.contains(DEFOLD_MARKER, ignoreCase = true)) {
                        responses += text
                    }
                }
            }
            val now = System.currentTimeMillis()
            responses
                .mapNotNull { parseEndpoint(it, now) }
                .distinctBy { it.address to it.port }
        }.onFailure { error ->
            logger.debug("SSDP engine discovery failed", error)
        }.getOrDefault(emptyList())
    }

    internal fun parseEndpoint(response: String, lastUpdatedMillis: Long): EngineEndpoint? {
        val location = response
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?: return null

        val uri = runCatching { URI(location) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: return null
        return EngineEndpoint(
            address = host,
            port = port,
            logPort = null,
            lastUpdatedMillis = lastUpdatedMillis
        )
    }

    private fun ssdpSearchPayload(): ByteArray = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: ").append(SSDP_HOST).append(':').append(SSDP_PORT).append("\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: 1\r\n")
        append("ST: ").append(SSDP_SEARCH_TARGET).append("\r\n")
        append("\r\n")
    }.toByteArray(Charsets.US_ASCII)
}
