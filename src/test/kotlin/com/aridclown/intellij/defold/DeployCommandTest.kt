package com.aridclown.intellij.defold

import com.aridclown.intellij.defold.actions.DeployCommandBuilder
import com.aridclown.intellij.defold.actions.DeployPreparation
import com.aridclown.intellij.defold.actions.DeployTarget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DeployCommandTest {
    @TempDir
    lateinit var tempDir: Path

    private fun builderWithTools(vararg available: String) = DeployCommandBuilder(
        pathProbe = { tool -> tool in available }
    )

    private fun writeArtifact(relativePath: String): Path {
        val artifact = tempDir.resolve(relativePath)
        Files.createDirectories(artifact.parent)
        Files.writeString(artifact, "binary-stub")
        return artifact
    }

    @Test
    fun `builds ios-deploy command with -b flag and ipa path`() {
        val ipa = writeArtifact("bundle/arm64-ios/MyGame.ipa")
        val builder = builderWithTools("ios-deploy")

        val command = builder.createCommand(DeployTarget.IOS, ipa)

        assertThat(command)
            .extracting({ it.exePath }, { it.parametersList.parameters })
            .containsExactly("ios-deploy", listOf("-b", ipa.toString()))
    }

    @Test
    fun `builds adb command with install verb and apk path`() {
        val apk = writeArtifact("bundle/armv7-android/MyGame.apk")
        val builder = builderWithTools("adb")

        val command = builder.createCommand(DeployTarget.ANDROID, apk)

        assertThat(command)
            .extracting({ it.exePath }, { it.parametersList.parameters })
            .containsExactly("adb", listOf("install", apk.toString()))
    }

    @Test
    fun `prepares an iOS deployment when an ipa is bundled and ios-deploy is on PATH`() {
        val ipa = writeArtifact("bundle/arm64-ios/MyGame.ipa")
        val builder = builderWithTools("ios-deploy")

        val prep = builder.prepareDeploy(tempDir.resolve("bundle"), DeployTarget.IOS)

        assertThat(prep).isInstanceOf(DeployPreparation.Ready::class.java)
        val ready = prep as DeployPreparation.Ready
        assertThat(ready)
            .extracting({ it.target }, { it.artifact }, { it.command.exePath }, { it.command.parametersList.parameters })
            .containsExactly(DeployTarget.IOS, ipa, "ios-deploy", listOf("-b", ipa.toString()))
    }

    @Test
    fun `prepares an Android deployment when an apk is bundled and adb is on PATH`() {
        val apk = writeArtifact("bundle/armv7-android/MyGame.apk")
        val builder = builderWithTools("adb")

        val prep = builder.prepareDeploy(tempDir.resolve("bundle"), DeployTarget.ANDROID)

        assertThat(prep).isInstanceOf(DeployPreparation.Ready::class.java)
        val ready = prep as DeployPreparation.Ready
        assertThat(ready)
            .extracting({ it.target }, { it.artifact }, { it.command.exePath }, { it.command.parametersList.parameters })
            .containsExactly(DeployTarget.ANDROID, apk, "adb", listOf("install", apk.toString()))
    }

    @Test
    fun `signals MissingTool instead of throwing when ios-deploy is not on PATH`() {
        val ipa = writeArtifact("bundle/arm64-ios/MyGame.ipa")
        val builder = builderWithTools() // no tools available

        val prep = builder.prepareDeploy(tempDir.resolve("bundle"), DeployTarget.IOS)

        assertThat(prep).isInstanceOf(DeployPreparation.MissingTool::class.java)
        val missing = prep as DeployPreparation.MissingTool
        assertThat(missing)
            .extracting({ it.tool }, { it.target }, { it.artifact })
            .containsExactly("ios-deploy", DeployTarget.IOS, ipa)
    }

    @Test
    fun `signals MissingTool instead of throwing when adb is not on PATH`() {
        val apk = writeArtifact("bundle/armv7-android/MyGame.apk")
        val builder = builderWithTools() // no tools available

        val prep = builder.prepareDeploy(tempDir.resolve("bundle"), DeployTarget.ANDROID)

        assertThat(prep).isInstanceOf(DeployPreparation.MissingTool::class.java)
        val missing = prep as DeployPreparation.MissingTool
        assertThat(missing)
            .extracting({ it.tool }, { it.target })
            .containsExactly("adb", DeployTarget.ANDROID)
    }

    @Test
    fun `signals NoArtifact when bundle directory does not exist`() {
        val builder = builderWithTools("ios-deploy", "adb")

        val prep = builder.prepareDeploy(tempDir.resolve("bundle"), DeployTarget.IOS)

        assertThat(prep).isInstanceOf(DeployPreparation.NoArtifact::class.java)
    }

    @Test
    fun `finds an ipa nested under bundle target subdirectory`() {
        val ipa = writeArtifact("bundle/arm64-ios/MyGame.ipa")
        val builder = builderWithTools("ios-deploy")

        val discovered = builder.findArtifact(tempDir.resolve("bundle"), DeployTarget.IOS)

        assertThat(discovered).isEqualTo(ipa)
    }

    @Test
    fun `finds an apk nested under bundle target subdirectory`() {
        val apk = writeArtifact("bundle/armv7-android/release/MyGame.apk")
        val builder = builderWithTools("adb")

        val discovered = builder.findArtifact(tempDir.resolve("bundle"), DeployTarget.ANDROID)

        assertThat(discovered).isEqualTo(apk)
    }

    @Test
    fun `auto-detects iOS when only an ipa is bundled`() {
        val ipa = writeArtifact("bundle/arm64-ios/MyGame.ipa")
        val builder = builderWithTools("ios-deploy", "adb")

        val prep = builder.prepareAutoDeploy(tempDir.resolve("bundle"))

        assertThat(prep).isInstanceOf(DeployPreparation.Ready::class.java)
        val ready = prep as DeployPreparation.Ready
        assertThat(ready)
            .extracting({ it.target }, { it.artifact })
            .containsExactly(DeployTarget.IOS, ipa)
    }

    @Test
    fun `auto-detects Android when only an apk is bundled`() {
        val apk = writeArtifact("bundle/armv7-android/MyGame.apk")
        val builder = builderWithTools("ios-deploy", "adb")

        val prep = builder.prepareAutoDeploy(tempDir.resolve("bundle"))

        assertThat(prep).isInstanceOf(DeployPreparation.Ready::class.java)
        val ready = prep as DeployPreparation.Ready
        assertThat(ready)
            .extracting({ it.target }, { it.artifact })
            .containsExactly(DeployTarget.ANDROID, apk)
    }
}
