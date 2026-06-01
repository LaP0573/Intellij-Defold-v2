package com.aridclown.intellij.defold.actions

import com.aridclown.intellij.defold.DefoldCoroutineService.Companion.launch
import com.aridclown.intellij.defold.DefoldProjectService.Companion.ensureConsole
import com.aridclown.intellij.defold.DefoldProjectService.Companion.rootProjectFolder
import com.aridclown.intellij.defold.process.BackgroundProcessRequest
import com.aridclown.intellij.defold.process.ProcessExecutor
import com.aridclown.intellij.defold.util.NotificationService.notifyError
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.streams.asSequence

enum class DeployTarget(
    val tool: String,
    val artifactExtension: String,
    val deployFlag: String
) {
    IOS(tool = "ios-deploy", artifactExtension = "ipa", deployFlag = "-b"),
    ANDROID(tool = "adb", artifactExtension = "apk", deployFlag = "install");

    fun matches(path: Path): Boolean = path.extension.lowercase(Locale.ROOT) == artifactExtension
}

/**
 * Locates bundled artifacts and builds the matching `ios-deploy` / `adb` command line.
 *
 * Kept tool-agnostic and side-effect free so it is unit-testable without an IntelliJ project.
 * Tool availability is probed via PATH (falls back to a stub probe in tests).
 */
class DeployCommandBuilder(
    private val pathProbe: (String) -> Boolean = ::isOnPath
) {
    fun findArtifact(bundleRoot: Path, target: DeployTarget): Path? {
        if (!bundleRoot.exists()) return null
        return Files.walk(bundleRoot).use { stream ->
            stream.asSequence()
                .filter { Files.isRegularFile(it) }
                .filter { target.matches(it) }
                .sortedByDescending { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
                .firstOrNull()
        }
    }

    fun findAnyArtifact(bundleRoot: Path): Pair<DeployTarget, Path>? {
        val ipa = findArtifact(bundleRoot, DeployTarget.IOS)
        val apk = findArtifact(bundleRoot, DeployTarget.ANDROID)
        return when {
            ipa != null && apk == null -> DeployTarget.IOS to ipa

            apk != null && ipa == null -> DeployTarget.ANDROID to apk

            ipa != null && apk != null -> {
                val ipaTs = runCatching { Files.getLastModifiedTime(ipa).toMillis() }.getOrDefault(0L)
                val apkTs = runCatching { Files.getLastModifiedTime(apk).toMillis() }.getOrDefault(0L)
                if (ipaTs >= apkTs) DeployTarget.IOS to ipa else DeployTarget.ANDROID to apk
            }

            else -> null
        }
    }

    fun isToolAvailable(target: DeployTarget): Boolean = pathProbe(target.tool)

    fun createCommand(target: DeployTarget, artifact: Path): GeneralCommandLine = GeneralCommandLine(target.tool, target.deployFlag, artifact.toString())

    fun prepareDeploy(bundleRoot: Path, target: DeployTarget): DeployPreparation {
        val artifact = findArtifact(bundleRoot, target)
            ?: return DeployPreparation.NoArtifact(target)
        if (!isToolAvailable(target)) return DeployPreparation.MissingTool(target, artifact)
        return DeployPreparation.Ready(target, artifact, createCommand(target, artifact))
    }

    fun prepareAutoDeploy(bundleRoot: Path): DeployPreparation {
        val detected = findAnyArtifact(bundleRoot) ?: return DeployPreparation.NoArtifact(null)
        val (target, artifact) = detected
        if (!isToolAvailable(target)) return DeployPreparation.MissingTool(target, artifact)
        return DeployPreparation.Ready(target, artifact, createCommand(target, artifact))
    }
}

sealed class DeployPreparation {
    data class Ready(
        val target: DeployTarget,
        val artifact: Path,
        val command: GeneralCommandLine
    ) : DeployPreparation()

    data class MissingTool(
        val target: DeployTarget,
        val artifact: Path
    ) : DeployPreparation() {
        val tool: String get() = target.tool
    }

    data class NoArtifact(val target: DeployTarget?) : DeployPreparation()
}

class DeployAction : DefoldProjectAction() {
    override fun actionPerformed(event: AnActionEvent) = withDefoldProject(event) { project ->
        project.launch { runDeploy(project) }
    }

    private fun runDeploy(project: Project) {
        val projectRoot = project.rootProjectFolder?.path?.let(::Path) ?: return
        val bundleRoot = projectRoot.resolve("bundle")
        val builder = DeployCommandBuilder()
        when (val prep = builder.prepareAutoDeploy(bundleRoot)) {
            is DeployPreparation.Ready -> {
                val console = project.ensureConsole(DEPLOY_CONSOLE_TITLE)
                ProcessExecutor(console).executeInBackground(
                    BackgroundProcessRequest(
                        project = project,
                        title = "Deploying ${prep.target.name.lowercase(Locale.ROOT)} bundle",
                        command = prep.command
                    )
                )
            }

            is DeployPreparation.MissingTool -> project.notifyError(
                title = "Defold deploy",
                content = "Cannot deploy: ${prep.tool} was not found on PATH. Install it and ensure it is on your PATH."
            )

            is DeployPreparation.NoArtifact -> project.notifyError(
                title = "Defold deploy",
                content = "No deployable artifact found under bundle/. Bundle the project first (.ipa for iOS, .apk for Android)."
            )
        }
    }
}

private const val DEPLOY_CONSOLE_TITLE = "Defold Deploy"

internal fun isOnPath(tool: String): Boolean {
    val pathEnv = System.getenv("PATH") ?: return false
    val isWindows = System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")
    val separator = System.getProperty("path.separator") ?: ":"
    val candidates = if (isWindows) {
        val pathExt = (System.getenv("PATHEXT") ?: ".EXE;.BAT;.CMD;.COM")
            .split(';')
            .map { it.lowercase(Locale.ROOT) }
        pathExt.map { ext -> if (tool.lowercase(Locale.ROOT).endsWith(ext)) tool else "$tool$ext" }
    } else {
        listOf(tool)
    }
    return pathEnv.split(separator).any { dir ->
        if (dir.isBlank()) return@any false
        candidates.any { candidate ->
            runCatching { Path(dir).resolve(candidate).exists() }.getOrDefault(false)
        }
    }
}
