package com.aridclown.intellij.defold.templates

/**
 * A single file produced by a scaffold call.
 *
 * `name` is the file name (no directory) and `content` is the full file body to write.
 */
data class ScaffoldFile(val name: String, val content: String)

/**
 * Pure content builders for Defold scaffolds. The folder context-menu actions invoke these
 * and write the resulting files into the target directory. Keeping the content generation
 * pure makes it straightforward to assert in unit tests without spinning up the IDE.
 */
object DefoldScaffold {
    fun gameObject(
        id: String,
        directoryProjectPath: String = "",
        includeFactory: Boolean = true
    ): List<ScaffoldFile> {
        val scriptName = "$id.script"
        val goName = "$id.go"
        val files = mutableListOf<ScaffoldFile>()
        files += ScaffoldFile(scriptName, scriptContent(id))
        files += ScaffoldFile(goName, gameObjectContent(id, projectPath(directoryProjectPath, scriptName)))
        if (includeFactory) {
            files += ScaffoldFile("$id.factory", factoryContent(projectPath(directoryProjectPath, goName)))
        }
        return files
    }

    fun gui(
        id: String,
        directoryProjectPath: String = "",
        includeGuiScript: Boolean = true
    ): List<ScaffoldFile> {
        val guiScriptName = "$id.gui_script"
        val files = mutableListOf<ScaffoldFile>()
        if (includeGuiScript) {
            files += ScaffoldFile(guiScriptName, guiScriptContent(id))
        }
        val scriptRef = if (includeGuiScript) projectPath(directoryProjectPath, guiScriptName) else ""
        files += ScaffoldFile("$id.gui", guiContent(scriptRef))
        return files
    }

    fun luaModule(id: String): List<ScaffoldFile> = listOf(ScaffoldFile("$id.lua", luaModuleContent(id)))

    private fun scriptContent(id: String): String = lifecycleScript(id)

    private fun guiScriptContent(id: String): String = lifecycleScript(id, includeFixedUpdate = false)

    private fun lifecycleScript(id: String, includeFixedUpdate: Boolean = true): String = buildString {
        appendLine("---@class ${id}_self")
        appendLine()
        appendLine("function init(self)")
        appendLine("\t-- Add initialization code here")
        appendLine("end")
        appendLine()
        appendLine("function final(self)")
        appendLine("\t-- Add finalization code here")
        appendLine("end")
        appendLine()
        appendLine("function update(self, dt)")
        appendLine("\t-- Add update code here")
        appendLine("end")
        if (includeFixedUpdate) {
            appendLine()
            appendLine("function fixed_update(self, dt)")
            appendLine("\t-- Add fixed-update code here")
            appendLine("end")
        }
        appendLine()
        appendLine("function on_message(self, message_id, message, sender)")
        appendLine("\t-- Add message-handling code here")
        appendLine("end")
        appendLine()
        appendLine("function on_input(self, action_id, action)")
        appendLine("\t-- Add input-handling code here")
        appendLine("end")
        appendLine()
        appendLine("function on_reload(self)")
        appendLine("\t-- Add reload-handling code here")
        append("end")
    }

    private fun gameObjectContent(id: String, scriptProjectPath: String): String = buildString {
        appendLine("components {")
        appendLine("  id: \"$id\"")
        appendLine("  component: \"$scriptProjectPath\"")
        appendLine("}")
        appendLine("embedded_components {")
        appendLine("  id: \"sprite\"")
        appendLine("  type: \"sprite\"")
        appendLine("  data: \"\"")
        appendLine("  position { x: 0.0 y: 0.0 z: 0.0 }")
        appendLine("  rotation { x: 0.0 y: 0.0 z: 0.0 w: 1.0 }")
        appendLine("}")
        appendLine("embedded_components {")
        appendLine("  id: \"collisionobject\"")
        appendLine("  type: \"collisionobject\"")
        appendLine("  data: \"\"")
        appendLine("  position { x: 0.0 y: 0.0 z: 0.0 }")
        append("  rotation { x: 0.0 y: 0.0 z: 0.0 w: 1.0 }\n}")
    }

    private fun guiContent(scriptProjectPath: String): String = buildString {
        appendLine("script: \"$scriptProjectPath\"")
        appendLine("background_color { x: 0.0 y: 0.0 z: 0.0 w: 0.0 }")
        appendLine("material: \"/builtins/materials/gui.material\"")
        append("adjust_reference: ADJUST_REFERENCE_PARENT")
    }

    private fun factoryContent(prototypeProjectPath: String): String = buildString {
        appendLine("prototype: \"$prototypeProjectPath\"")
        appendLine("load_dynamically: false")
        append("dynamic_prototype: false")
    }

    private fun luaModuleContent(id: String): String = buildString {
        appendLine("---@class $id")
        appendLine("local M = {}")
        appendLine()
        append("return M")
    }

    /**
     * Builds a Defold project-relative reference like `/foo/bar/baz.script`. Defold resolves
     * resource references from the project root, so the leading slash is required.
     */
    private fun projectPath(directoryProjectPath: String, fileName: String): String {
        val cleaned = directoryProjectPath.trim().trim('/')
        return if (cleaned.isEmpty()) "/$fileName" else "/$cleaned/$fileName"
    }
}
