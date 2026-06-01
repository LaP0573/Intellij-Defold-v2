package com.aridclown.intellij.defold.settings

import com.aridclown.intellij.defold.DefoldDefaults
import com.aridclown.intellij.defold.Platform
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.singleDir
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.singleFile
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align.Companion.FILL
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class DefoldSettingsConfigurable :
    SearchableConfigurable,
    Configurable.NoScroll {
    private val settings = DefoldSettings.getInstance()
    private val cells = mutableListOf<EditableField>()
    private lateinit var installCell: Cell<TextFieldWithBrowseButton>

    override fun getId(): String = "com.aridclown.intellij.defold.settings"

    override fun getDisplayName(): String = "Defold"

    override fun getHelpTopic(): String? = null

    override fun createComponent(): JComponent = panel {
        row("Install directory:") {
            installCell =
                textFieldWithBrowseButton(singleDir())
                    .align(FILL)
                    .bindText(
                        { settings.installPath() ?: defaultSuggestion().orEmpty() },
                        { /* applied below in apply() */ }
                    )
        }
        group("iOS signing") {
            fileRow("Provisioning profile (debug):", settings::iosProvisioningDebug, settings::setIosProvisioningDebug)
            fileRow("Provisioning profile (release):", settings::iosProvisioningRelease, settings::setIosProvisioningRelease)
            textRow("Code-signing identity (debug):", settings::iosIdentityDebug, settings::setIosIdentityDebug)
            textRow("Code-signing identity (release):", settings::iosIdentityRelease, settings::setIosIdentityRelease)
        }
        group("Android signing") {
            fileRow("Keystore:", settings::androidKeystore, settings::setAndroidKeystore)
            textRow("Keystore password:", settings::androidKeystorePass, settings::setAndroidKeystorePass)
            textRow("Keystore alias:", settings::androidKeystoreAlias, settings::setAndroidKeystoreAlias)
        }
        group("Extender / private dependencies") {
            textRow("Build server URL:", settings::buildServer, settings::setBuildServer)
            textRow("Private deps email:", settings::privateDepEmail, settings::setPrivateDepEmail)
            textRow("Private deps auth token:", settings::privateDepAuth, settings::setPrivateDepAuth)
        }
    }

    override fun isModified(): Boolean {
        if (!::installCell.isInitialized) return false
        if (installCell.component.text.trim() != (settings.installPath() ?: defaultSuggestion().orEmpty())) {
            return true
        }
        return cells.any { it.isModified() }
    }

    override fun apply() {
        settings.setInstallPath(installCell.component.text.trim())
        cells.forEach(EditableField::apply)
    }

    override fun reset() {
        if (::installCell.isInitialized) {
            installCell.component.text = settings.installPath() ?: defaultSuggestion().orEmpty()
        }
        cells.forEach(EditableField::reset)
    }

    private fun com.intellij.ui.dsl.builder.Panel.fileRow(
        label: String,
        get: () -> String?,
        set: (String?) -> Unit
    ) {
        row(label) {
            val cell = textFieldWithBrowseButton(singleFile())
                .align(FILL)
            cell.component.text = get().orEmpty()
            cells += EditableField(
                read = { cell.component.text.trim() },
                stored = { get().orEmpty() },
                write = { value -> set(value.ifBlank { null }) },
                resetView = { cell.component.text = get().orEmpty() }
            )
        }
    }

    private fun com.intellij.ui.dsl.builder.Panel.textRow(
        label: String,
        get: () -> String?,
        set: (String?) -> Unit
    ) {
        row(label) {
            val cell = textField().align(FILL)
            cell.component.text = get().orEmpty()
            cells += EditableField(
                read = { (cell.component as JBTextField).text.trim() },
                stored = { get().orEmpty() },
                write = { value -> set(value.ifBlank { null }) },
                resetView = { (cell.component as JBTextField).text = get().orEmpty() }
            )
        }
    }

    private fun defaultSuggestion(): String? = DefoldDefaults.installPathSuggestion(Platform.current())

    private class EditableField(
        val read: () -> String,
        val stored: () -> String,
        val write: (String) -> Unit,
        val resetView: () -> Unit
    ) {
        fun isModified(): Boolean = read() != stored()
        fun apply() = write(read())
        fun reset() = resetView()
    }
}
