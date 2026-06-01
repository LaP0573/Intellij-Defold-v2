package com.aridclown.intellij.defold.actions

import com.aridclown.intellij.defold.AndroidBundleFormat
import com.aridclown.intellij.defold.BuildRequest
import com.aridclown.intellij.defold.BundleCommandBuilder
import com.aridclown.intellij.defold.BundleOptions
import com.aridclown.intellij.defold.BundleTarget
import com.aridclown.intellij.defold.BundleVariant
import com.aridclown.intellij.defold.DefoldCoroutineService.Companion.launch
import com.aridclown.intellij.defold.DefoldEditorConfig
import com.aridclown.intellij.defold.ProjectBuilder
import com.aridclown.intellij.defold.TextureCompressionMode
import com.aridclown.intellij.defold.process.ProcessExecutor
import com.aridclown.intellij.defold.util.NotificationService.notifyInfo
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import javax.swing.JComponent

/**
 * Bundles the Defold project for one of the six supported targets via Bob.
 *
 * Pops a platform/options picker, then dispatches a Bob bundle invocation through
 * the existing [ProjectBuilder]. The constructed Bob argv is produced by
 * [BundleCommandBuilder].
 */
class BundleAction : DefoldProjectAction() {
    override fun actionPerformed(event: AnActionEvent) = withDefoldProject(event) { project ->
        withDefoldConfig(project) { config ->
            val state = BundlePickerState()
            if (!BundleDialog(project, state).showAndGet()) return@withDefoldConfig

            launchBundle(project, config, state.target, state.toOptions())
        }
    }

    private fun launchBundle(
        project: Project,
        config: DefoldEditorConfig,
        target: BundleTarget,
        options: BundleOptions
    ) {
        val commands = BundleCommandBuilder.build(target, options)
        project.notifyInfo(
            title = "Defold",
            content = "Bundling ${target.displayName} (${options.variant.flag})…"
        )
        project.launch {
            ProjectBuilder(ProcessExecutor()).buildProject(
                BuildRequest(
                    project = project,
                    config = config,
                    commands = commands
                ),
                buildMessage = "Bundling Defold project (${target.displayName})"
            )
        }
    }
}

internal class BundlePickerState {
    var target: BundleTarget = BundleTarget.MACOS
    var variant: BundleVariant = BundleVariant.RELEASE
    var androidBundleFormat: AndroidBundleFormat = AndroidBundleFormat.AAB
    var textureCompression: TextureCompressionMode? = null
    var buildReportHtml: Boolean = false
    var withSymbols: Boolean = false
    var liveUpdate: Boolean = false

    fun toOptions(): BundleOptions = BundleOptions(
        variant = variant,
        androidBundleFormat = androidBundleFormat,
        textureCompression = textureCompression,
        buildReportHtml = buildReportHtml,
        withSymbols = withSymbols,
        liveUpdate = liveUpdate
    )
}

internal class BundleDialog(
    project: Project,
    private val state: BundlePickerState
) : DialogWrapper(project, true) {
    init {
        title = "Bundle Defold Project"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Platform:") {
            comboBox(BundleTarget.entries.toList())
                .applyToComponent {
                    renderer = SimpleListCellRenderer.create("") { (it as BundleTarget?)?.displayName.orEmpty() }
                }
                .bindItem(
                    { state.target },
                    { state.target = it ?: BundleTarget.MACOS }
                )
        }
        row("Variant:") {
            comboBox(BundleVariant.entries.toList())
                .applyToComponent {
                    renderer = SimpleListCellRenderer.create("") { (it as BundleVariant?)?.flag.orEmpty() }
                }
                .bindItem(
                    { state.variant },
                    { state.variant = it ?: BundleVariant.RELEASE }
                )
        }
        row("Android format:") {
            comboBox(AndroidBundleFormat.entries.toList())
                .applyToComponent {
                    renderer = SimpleListCellRenderer.create("") { (it as AndroidBundleFormat?)?.flag.orEmpty() }
                }
                .bindItem(
                    { state.androidBundleFormat },
                    { state.androidBundleFormat = it ?: AndroidBundleFormat.AAB }
                )
        }.rowComment("Only used when Platform = Android")
        row("Texture compression:") {
            val choices = listOf<TextureCompressionMode?>(null) + TextureCompressionMode.entries.toList()
            comboBox(choices)
                .applyToComponent {
                    renderer = SimpleListCellRenderer.create("(default)") { (it as TextureCompressionMode?)?.flag.orEmpty() }
                }
                .bindItem(state::textureCompression.toNullableProperty())
        }
        row {
            checkBox("Generate HTML build report (--build-report-html)")
                .bindSelected(state::buildReportHtml)
        }
        row {
            checkBox("Include debug symbols (--with-symbols)")
                .bindSelected(state::withSymbols)
        }
        row {
            checkBox("Live update (--liveupdate yes)")
                .bindSelected(state::liveUpdate)
        }
    }
}
