package com.aridclown.intellij.defold.templates

import com.aridclown.intellij.defold.DefoldProjectService.Companion.isDefoldProject
import com.aridclown.intellij.defold.DefoldProjectService.Companion.rootProjectFolder
import com.aridclown.intellij.defold.util.DefoldIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * Base class for the folder context-menu scaffolders. Each subclass chooses a label, an
 * input-dialog title, and the [DefoldScaffold] call that turns the user-supplied id into
 * a list of files to drop next to the right-clicked folder.
 */
abstract class DefoldScaffoldAction(
    text: String,
    description: String
) : DumbAwareAction(text, description, DefoldIcons.defoldIcon) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        val targetFile = event.getData(CommonDataKeys.VIRTUAL_FILE)
        event.presentation.isEnabledAndVisible =
            project?.isDefoldProject == true && targetFile?.isDirectory == true
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val directory = event.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { it.isDirectory } ?: return

        val id = Messages.showInputDialog(
            project,
            "Identifier:",
            inputTitle,
            null,
            "",
            DefoldIdentifierValidator
        )?.trim() ?: return
        if (id.isEmpty()) return

        val files = build(id, project.directoryProjectPath(directory))
        DefoldScaffoldWriter.write(project, directory, files)
    }

    protected abstract val inputTitle: String

    protected abstract fun build(id: String, directoryProjectPath: String): List<ScaffoldFile>

    private fun Project.directoryProjectPath(directory: VirtualFile): String {
        val root = rootProjectFolder ?: return ""
        return VfsUtilCore.getRelativePath(directory, root) ?: ""
    }
}

internal object DefoldIdentifierValidator : InputValidatorEx {
    private val pattern = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    override fun getErrorText(inputString: String?): String? {
        val value = inputString?.trim().orEmpty()
        return when {
            value.isEmpty() -> "Identifier cannot be empty"
            !pattern.matches(value) -> "Use letters, digits, and underscores (must not start with a digit)"
            else -> null
        }
    }
}
