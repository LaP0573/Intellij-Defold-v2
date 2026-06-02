package com.aridclown.intellij.defold.templates

class CreateGuiAction :
    DefoldScaffoldAction(
        text = "Defold Gui",
        description = "Scaffold a Defold gui (.gui + gui_script)"
    ) {
    override val inputTitle: String = "New Defold Gui"

    override fun build(id: String, directoryProjectPath: String): List<ScaffoldFile> = DefoldScaffold.gui(id, directoryProjectPath, includeGuiScript = true)
}
