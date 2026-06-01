package com.aridclown.intellij.defold

import com.aridclown.intellij.defold.DefoldConstants.DEFAULT_MOBDEBUG_PORT
import com.aridclown.intellij.defold.DefoldConstants.INI_DEBUG_INIT_SCRIPT_VALUE
import com.aridclown.intellij.defold.EngineDiscoveryService.Companion.getEngineDiscoveryService
import com.aridclown.intellij.defold.process.ProcessExecutor
import com.aridclown.intellij.defold.util.printError
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.pathString

/**
 * Handles launching the Defold engine after a successful build
 */
class EngineRunner(
    private val processExecutor: ProcessExecutor
) {
    companion object {
        // Defold engine default service port. Surfaces /reload and other HTTP endpoints
        // the hot-reload service talks to.
        const val DEFAULT_SERVICE_PORT: Int = 8001
    }

    fun launchEngine(
        runRequest: RunRequest,
        enginePath: Path
    ): OSProcessHandler? = with(runRequest) {
        runCatching {
            val workspace = project.basePath ?: error("Project has no base path")
            val command =
                GeneralCommandLine(enginePath.toAbsolutePath().pathString)
                    .withWorkingDirectory(Path(workspace))
                    .applyEnvironment(envData)
                    // Always expose the engine service so hot reload works for plain Run too,
                    // not just the debug branch (see HotReload deep-dive in docs/).
                    .withEnvironment("DM_SERVICE_PORT", (serverPort ?: DEFAULT_SERVICE_PORT).toString())

            if (enableDebugScript) {
                val port = debugPort ?: DEFAULT_MOBDEBUG_PORT
                command
                    .withParameters("--config=bootstrap.debug_init_script=$INI_DEBUG_INIT_SCRIPT_VALUE")
                    .withEnvironment("MOBDEBUG_PORT", port.toString())
            }

            processExecutor
                .execute(command)
                .also { handler -> project.getEngineDiscoveryService().attachToProcess(handler, debugPort) }
        }.onFailure { throwable ->
            console.printError("Failed to launch dmengine: ${throwable.message}")
        }.getOrNull()
    }
}
