package com.aridclown.intellij.defold.logging

import com.intellij.openapi.diagnostic.Logger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.Closeable
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket client that streams log lines from a running Defold game's Remotery server.
 *
 * Defold exposes Remotery on the engine's HTTP service port; the WebSocket endpoint is
 * upgraded from `/rmt`. Binary frames carry `LOGM` messages whose payload is a length-prefixed
 * UTF-8 string (see [RemoteryFrameDecoder]). Each decoded line is forwarded to [onLine]; the
 * client is responsible for buffering / styling.
 *
 * Lifecycle:
 *  - [connect] opens the socket and starts decoding asynchronously.
 *  - [close] tears the socket down idempotently — safe to call from any thread.
 */
internal class RemoteryLogClient(
    private val host: String,
    private val port: Int,
    private val onLine: (String) -> Unit,
    private val onError: (String, Throwable?) -> Unit = { _, _ -> },
    private val httpClient: OkHttpClient = defaultClient
) : Closeable {
    private val decoder = RemoteryFrameDecoder()
    private val decoderLock = Any()
    private val closed = AtomicBoolean(false)

    @Volatile private var socket: WebSocket? = null

    /** Open the WebSocket. Returns immediately; events are delivered asynchronously. */
    fun connect() {
        if (closed.get()) return
        val request = Request.Builder().url("ws://$host:$port/rmt").build()
        socket = httpClient.newWebSocket(request, Listener())
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket?.close(NORMAL_CLOSURE, "closed by IDE") }
        socket = null
    }

    private fun handleFrames(bytes: ByteArray) {
        val frames = synchronized(decoderLock) {
            decoder.feed(bytes)
            decoder.drain()
        }
        for (frame in frames) {
            when (frame) {
                is RemoteryFrame.LogMessage -> onLine(frame.text)
                is RemoteryFrame.Unknown -> Unit
                is RemoteryFrame.Malformed -> onError("Remotery frame ${frame.id}: ${frame.reason}", null)
            }
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(
            webSocket: WebSocket,
            bytes: ByteString
        ) {
            handleFrames(bytes.toByteArray())
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String
        ) {
            onLine(text)
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String
        ) {
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?
        ) {
            if (closed.get()) return
            LOG.debug("Remotery WebSocket to $host:$port failed", t)
            onError("Lost Remotery connection to $host:$port", t)
        }
    }

    companion object {
        private const val NORMAL_CLOSURE: Int = 1000
        private val LOG = Logger.getInstance(RemoteryLogClient::class.java)

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .pingInterval(Duration.ofSeconds(20))
                .build()
        }
    }
}
