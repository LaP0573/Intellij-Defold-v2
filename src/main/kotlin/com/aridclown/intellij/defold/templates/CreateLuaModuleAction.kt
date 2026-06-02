package com.aridclown.intellij.defold.templates

class CreateLuaModuleAction :
    DefoldScaffoldAction(
        text = "Defold Lua Module",
        description = "Scaffold a Defold lua module (.lua)"
    ) {
    override val inputTitle: String = "New Defold Lua Module"

    override fun build(id: String, directoryProjectPath: String): List<ScaffoldFile> = DefoldScaffold.luaModule(id)
}
