package com.aridclown.intellij.defold.logging

import com.aridclown.intellij.defold.util.SimpleHttpClient
import com.intellij.openapi.diagnostic.Logger
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration

/**
 * SSDP/UPnP discovery for Defold games already running on the local network.
 *
 * The plugin's [com.aridclown.intellij.defold.EngineDiscoveryService] only sees engines it
 * launched itself (it scrapes their stdout). To stream logs from games started by the Defold
 * editor — or anywhere else — we send an SSDP `M-SEARCH` for `upnp:rootdevice`, keep responses
 * whose `SERVER` header mentions Defold, fetch the `LOCATION` descriptor over HTTP, and parse
 * the engine's log port out of the XML.
 */
class RunningGameDiscovery(
    private val multicastAddress: String = SSDP_MULTICAST_ADDR,
    private val multicastPort: Int = SSDP_PORT,
    private val timeout: Duration = Duration.ofSeconds(2),
    private val httpGet: (String, Duration) -> String? = { url, t ->
        runCatching { SimpleHttpClient.get(url, t).body }.getOrNull()
    }
) {
    /**
     * Run one M-SEARCH round and return every Defold game that answered.
     *
     * Blocking — call from a background coroutine, never the EDT. Each response is parsed
     * independently so one malformed descriptor doesn't drop the others.
     */
    fun discover(): List<DiscoveredGame> {
        val responses = sendMSearch()
        return responses.mapNotNull { response ->
            val server = response.headers["SERVER"] ?: return@mapNotNull null
            if (!server.contains(DEFOLD_TOKEN, ignoreCase = true)) return@mapNotNull null
            val location = response.headers["LOCATION"] ?: return@mapNotNull null
            buildGame(location, server)
        }
    }

    private fun buildGame(
        location: String,
        server: String
    ): DiscoveredGame? {
        val xml = httpGet(location, timeout) ?: return null
        val host = hostOf(location) ?: return null
        val logPort = extractLogPort(xml)
        val name = extractTagText(xml, "friendlyName") ?: "Defold game"
        return DiscoveredGame(name, host, logPort, server, location)
    }

    private fun sendMSearch(): List<SsdpResponse> {
        val payload = (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $multicastAddress:$multicastPort\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 1\r\n" +
                "ST: upnp:rootdevice\r\n" +
                "\r\n"
            ).toByteArray(UTF_8)

        val responses = mutableListOf<SsdpResponse>()
        MulticastSocket().use { socket ->
            socket.reuseAddress = true
            socket.soTimeout = timeout.toMillis().toInt()
            val target = InetSocketAddress(InetAddress.getByName(multicastAddress), multicastPort)
            socket.send(DatagramPacket(payload, payload.size, target))

            val buf = ByteArray(MAX_DATAGRAM_BYTES)
            val deadline = System.currentTimeMillis() + timeout.toMillis()
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    break
                }
                val text = String(packet.data, 0, packet.length, UTF_8)
                parseResponse(text)?.let(responses::add)
            }
        }
        return responses
    }

    companion object {
        const val SSDP_MULTICAST_ADDR: String = "239.255.255.250"
        const val SSDP_PORT: Int = 1900
        const val DEFOLD_TOKEN: String = "Defold"
        private const val MAX_DATAGRAM_BYTES: Int = 4096
        private val LOG = Logger.getInstance(RunningGameDiscovery::class.java)
        private val LOG_PORT_TAGS = listOf("defold:logPort", "logPort", "log_port")

        internal fun parseResponse(raw: String): SsdpResponse? {
            val lines = raw.split("\r\n", "\n").filter { it.isNotBlank() }
            if (lines.isEmpty() || !lines.first().startsWith("HTTP/")) return null
            val headers = lines
                .drop(1)
                .mapNotNull { line ->
                    val colon = line.indexOf(':')
                    if (colon <= 0) null else line.substring(0, colon).uppercase() to line.substring(colon + 1).trim()
                }.toMap()
            return SsdpResponse(headers)
        }

        internal fun extractLogPort(xml: String): Int? {
            for (tag in LOG_PORT_TAGS) {
                extractTagText(xml, tag)?.toIntOrNull()?.let { return it }
            }
            return null
        }

        internal fun extractTagText(
            xml: String,
            tag: String
        ): String? {
            val open = "<$tag>"
            val close = "</$tag>"
            val start = xml.indexOf(open).takeIf { it >= 0 } ?: return null
            val end = xml.indexOf(close, start + open.length).takeIf { it >= 0 } ?: return null
            return xml.substring(start + open.length, end).trim().ifEmpty { null }
        }

        private fun hostOf(location: String): String? = runCatching {
            java.net.URI(location).host
        }.getOrNull()
    }

    internal data class SsdpResponse(
        val headers: Map<String, String>
    )
}

data class DiscoveredGame(
    val name: String,
    val host: String,
    val logPort: Int?,
    val server: String,
    val descriptorUrl: String
)
