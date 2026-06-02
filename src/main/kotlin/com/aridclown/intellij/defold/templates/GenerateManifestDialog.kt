package com.aridclown.intellij.defold.templates

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.panel
import javax.swing.ButtonGroup
import javax.swing.JComponent

/**
 * Modal dialog letting the user pick the exclusion set and graphics adapter
 * fed to [AppManifestBuilder]. The result is exposed via [config] after the
 * dialog closes with OK.
 */
class GenerateManifestDialog(
    project: Project,
    initial: AppManifestConfig = AppManifestConfig()
) : DialogWrapper(project, true) {
    private val exclusionChecks: Map<AppManifestExclusion, JBCheckBox> =
        AppManifestExclusion.entries.associateWith { exclusion ->
            JBCheckBox(exclusion.displayName).apply {
                isSelected = exclusion in initial.exclusions
            }
        }

    private val graphicsRadios: Map<GraphicsAdapter, JBRadioButton> =
        GraphicsAdapter.entries.associateWith { adapter ->
            JBRadioButton(adapter.displayName).apply {
                isSelected = adapter == initial.graphics
            }
        }

    init {
        title = "Generate App Manifest"
        val group = ButtonGroup()
        graphicsRadios.values.forEach(group::add)
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            cell(JBLabel("Exclude built-in modules:"))
        }
        for ((_, checkBox) in exclusionChecks) {
            row {
                cell(checkBox)
            }
        }
        row {
            cell(JBLabel("Graphics adapter:"))
        }
        for ((_, radio) in graphicsRadios) {
            row {
                cell(radio)
            }
        }
    }

    val config: AppManifestConfig
        get() = AppManifestConfig(
            exclusions = exclusionChecks.filterValues(JBCheckBox::isSelected).keys,
            graphics = graphicsRadios.entries.firstOrNull { it.value.isSelected }?.key ?: GraphicsAdapter.BOTH
        )
}
