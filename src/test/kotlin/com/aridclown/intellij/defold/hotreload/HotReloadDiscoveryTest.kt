package com.aridclown.intellij.defold.hotreload

import com.aridclown.intellij.defold.DefoldCoroutineService
import com.aridclown.intellij.defold.DefoldEditorConfig
import com.aridclown.intellij.defold.DefoldProjectService
import com.aridclown.intellij.defold.EngineDiscoveryService
import com.aridclown.intellij.defold.EngineDiscoveryService.Companion.getEngineDiscoveryService
import com.aridclown.intellij.defold.EngineEndpoint
import com.aridclown.intellij.defold.EngineRunner
import com.aridclown.intellij.defold.LaunchConfigs
import com.aridclown.intellij.defold.RunRequest
import com.aridclown.intellij.defold.process.ProcessExecutor
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.project.Project
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.absolutePathString

/**
 * Coverage for the broadened hot-reload discovery surface: a non-debug Run must still
 * give the engine a reachable service port, an SSDP-discovered engine must keep the action
 * enabled (without probing SSDP inline on the update path), and when no direct engine endpoint
 * is found the action must delegate to the running Defold editor.
 */
class HotReloadDiscoveryTest {
    private val project = mockk<Project>(relaxed = true)
    private val console = mockk<ConsoleView>(relaxed = true)
    private val executor = mockk<ProcessExecutor>()
    private val engineDiscovery = mockk<EngineDiscoveryService>(relaxed = true)
    private val processHandler = mockk<OSProcessHandler>(relaxed = true)

    @TempDir
    lateinit var projectDir: Path

    private val editorConfig = DefoldEditorConfig(
        version = "1.0",
        editorJar = "editor.jar",
        javaBin = "java",
        jarBin = "jar",
        launchConfig =
        LaunchConfigs.Config(
            buildPlatform = "x86",
            libexecBinPath = "bin",
            executable = "dmengine"
        )
    )

    @BeforeEach
    fun setUp() {
        mockkObject(EngineDiscoveryService.Companion)
        every { project.getEngineDiscoveryService() } returns engineDiscovery
        every { project.basePath } returns projectDir.absolutePathString()
        // The SSDP enablement cache refreshes via project.launch { } on a background coroutine.
        // Stub the coroutine service so the launch is a no-op (the captured block never runs) and
        // the relaxed generic getService() can't ClassCast on the reified service<T>() lookup.
        every { project.getService(DefoldCoroutineService::class.java) } returns mockk<DefoldCoroutineService>(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
        unmockkObject(EngineDiscoveryService.Companion)
    }

    @Test
    fun `plain run exposes the engine service port so it is reachable for hot reload`() {
        val request = RunRequest(
            project = project,
            config = editorConfig,
            console = console,
            enableDebugScript = false,
            envData = EnvironmentVariablesData.create(emptyMap(), true)
        )
        val commandSlot = slot<GeneralCommandLine>()
        every { executor.execute(capture(commandSlot)) } returns processHandler

        val launched = EngineRunner(executor).launchEngine(request, Path.of("/tmp/dmengine"))

        assertThat(launched).isEqualTo(processHandler)
        assertThat(commandSlot.captured.environment)
            .containsEntry("DM_SERVICE_PORT", EngineRunner.DEFAULT_SERVICE_PORT.toString())
            .doesNotContainKey("MOBDEBUG_PORT")
        verify(exactly = 1) { engineDiscovery.attachToProcess(processHandler, null) }
    }

    @Test
    fun `editor delegation fallback runs when no direct engine endpoint is reachable`() {
        val mockDefoldService = mockk<DefoldProjectService>(relaxed = true)
        every { project.getService(DefoldProjectService::class.java) } returns mockDefoldService

        val service = HotReloadService(project)
        val dependencies = mockk<HotReloadDependencies>()
        every { dependencies.obtainConsole() } returns console
        every { dependencies.ensureReachableEngines(console) } returns emptyList<EngineEndpoint>()
        coEvery { dependencies.delegateToEditor(console) } returns true

        service.setDependenciesForTesting(dependencies)

        runBlocking { service.performHotReload() }

        coVerify(exactly = 1) { dependencies.delegateToEditor(console) }
        coVerify(exactly = 0) { dependencies.buildProject(any()) }
        verify(exactly = 0) { dependencies.sendResourceReload(any(), any()) }
    }

    @Test
    fun `action enables for an SSDP-only target once background discovery populates the cache`() {
        every { engineDiscovery.currentEndpoints() } returns emptyList()

        val service = HotReloadService(project)
        val dependencies = mockk<HotReloadDependencies>(relaxed = true)
        every { dependencies.discoverSsdpEngines() } returns
            listOf(EngineEndpoint(address = "127.0.0.1", port = 8002, logPort = null, lastUpdatedMillis = 0L))
        service.setDependenciesForTesting(dependencies)

        // No direct endpoint and no running editor, so SSDP is the only layer that could find the
        // game. SSDP is a blocking multicast and must never be probed inline on the action-update
        // path, so the action stays disabled until a background refresh warms the cache.
        assertThat(service.hasReloadTarget()).isFalse()
        verify(exactly = 0) { dependencies.discoverSsdpEngines() }

        // Background SSDP discovery completes (production runs this off the EDT/update path)...
        service.refreshSsdpCache()
        verify(exactly = 1) { dependencies.discoverSsdpEngines() }

        // ...and now the action is enabled purely via the SSDP discovery layer.
        assertThat(service.hasReloadTarget()).isTrue()
    }
}
