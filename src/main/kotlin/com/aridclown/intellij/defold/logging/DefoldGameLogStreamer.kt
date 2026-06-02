package com.aridclown.intellij.defold.logging

import com.aridclown.intellij.defold.DefoldCoroutineService.Companion.launch
import com.aridclown.intellij.defold.DefoldProjectService.Companion.ensureConsole
import com.aridclown.intellij.defold.process.ProcessTextProcessor
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level.PROJECT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges [RunningGameDiscovery] and [RemoteryLogClient] so logs from games the IDE did not
 * spawn still land in the Defold console with the same severity styling + `file:line`
 * hyperlinks as logs from self-launched engines.
 *
 * The service is project-scoped and idempotent: calling [attach] for a game already being
 * streamed is a no-op. Each streamer routes raw text through a [ProcessTextProcessor] so
 * partial UTF-8 lines, embedded newlines, and severity continuation behave exactly like the
 * `OSProcessHandler` path uses.
 */
@Service(PROJECT)
class DefoldGameLogStreamer(
    private val project: Project
) {
    private val active = ConcurrentHashMap<String, GameStream>()

    /**
     * Search the local network for running Defold games and attach a Remotery log stream to
     * every one that exposes a log port. Safe to call repeatedly.
     */
    fun discoverAndStream(discovery: RunningGameDiscovery = RunningGameDiscovery()) {
        project.launch {
            val games = withContext(Dispatchers.IO) { runCatching { discovery.discover() }.getOrDefault(emptyList()) }
            for (game in games) {
                val port = game.logPort ?: continue
                attach(game.copy(logPort = port))
            }
        }
    }

    /** Attach to a specific discovered game; reuses an existing stream when already attached. */
    fun attach(game: DiscoveredGame) {
        val port = game.logPort ?: return
        val key = "${game.host}:$port"
        active.computeIfAbsent(key) { GameStream(game, port).also(GameStream::start) }
    }

    /** Stop streaming everything attached so far. */
    fun stopAll() {
        active.values.forEach(GameStream::close)
        active.clear()
    }

    private inner class GameStream(
        private val game: DiscoveredGame,
        port: Int
    ) : Closeable {
        private val textProcessor = ProcessTextProcessor()
        private val console: ConsoleView by lazy { project.ensureConsole(CONSOLE_TITLE) }
        private val client = RemoteryLogClient(
            host = game.host,
            port = port,
            onLine = ::handleLine,
            onError = { message, _ -> printError(message) }
        )

        fun start() {
            printSystem("Streaming logs from ${game.name} (${game.host})")
            client.connect()
        }

        override fun close() {
            client.close()
        }

        private fun handleLine(text: String) {
            val chunk = if (text.endsWith("\n")) text else "$text\n"
            textProcessor.processChunk(chunk) { line, severity ->
                val type = ConsoleViewContentType.getConsoleViewType(severity.outputKey)
                console.print(line, type)
            }
        }

        private fun printSystem(message: String) {
            console.print("[Defold] $message\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        }

        private fun printError(message: String) {
            console.print("[Defold] $message\n", ConsoleViewContentType.ERROR_OUTPUT)
            LOG.debug(message)
        }
    }

    companion object {
        private const val CONSOLE_TITLE = "Defold"
        private val LOG = Logger.getInstance(DefoldGameLogStreamer::class.java)

        fun Project.defoldGameLogStreamer(): DefoldGameLogStreamer = service<DefoldGameLogStreamer>()
    }
}
