package com.aridclown.intellij.defold.templates

class CreateGameObjectAction :
    DefoldScaffoldAction(
        text = "Defold Game Object",
        description = "Scaffold a Defold game object (.go + script + factory)"
    ) {
    override val inputTitle: String = "New Defold Game Object"

    override fun build(id: String, directoryProjectPath: String): List<ScaffoldFile> = DefoldScaffold.gameObject(id, directoryProjectPath, includeFactory = true)
}
