package com.aridclown.intellij.defold

import com.aridclown.intellij.defold.util.SimpleHttpClient
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

internal class EditorHttpClient private constructor(
    private val baseUrl: HttpUrl,
    private val availableCommands: Set<String>
) {
    fun supports(command: String): Boolean = availableCommands.contains(command)

    suspend fun sendCommand(command: String): Boolean = withContext(Dispatchers.IO) {
        val url = baseUrl.newBuilder()
            .addPathSegment("command")
            .addPathSegment(command)
            .build()

        runCatching {
            SimpleHttpClient.postBytes(
                url.toString(),
                ByteArray(0),
                contentType = "text/plain",
                timeout = REQUEST_TIMEOUT
            ).code in 200..299
        }.onFailure { error ->
            logger.warn("Failed to contact Defold editor", error)
        }.getOrDefault(false)
    }

    companion object {
        private val logger = Logger.getInstance(EditorHttpClient::class.java)
        private val REQUEST_TIMEOUT = Duration.ofSeconds(5)

        suspend fun connect(projectPath: String): EditorHttpClient? = withContext(Dispatchers.IO) {
            val port = readEditorPort(projectPath) ?: return@withContext null
            val baseUrl = HttpUrl.Builder()
                .scheme("http")
                .host("127.0.0.1")
                .port(port)
                .build()

            val commandUrl = baseUrl.newBuilder()
                .addPathSegment("command")
                .build()

            val response = try {
                SimpleHttpClient.get(commandUrl.toString(), REQUEST_TIMEOUT)
            } catch (e: IOException) {
                logger.debug("Failed to query editor command endpoint", e)
                return@withContext null
            }

            if (response.code !in 200..299 || response.body.isNullOrEmpty()) {
                return@withContext null
            }

            val json = JsonParser.parseString(response.body).asJsonObject
            val commands = json.keySet()

            EditorHttpClient(baseUrl, commands)
        }

        private fun readEditorPort(projectPath: String): Int? {
            val portFile = Path.of(projectPath, ".internal", "editor.port")
            if (!Files.exists(portFile)) return null

            return try {
                Files.readString(portFile)
                    .trim()
                    .toInt()
            } catch (e: Exception) {
                logger.debug("Failed to read editor port file", e)
                null
            }
        }
    }
}
