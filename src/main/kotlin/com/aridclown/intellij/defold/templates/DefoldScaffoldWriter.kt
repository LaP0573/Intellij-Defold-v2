package com.aridclown.intellij.defold.templates

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Writes a set of [ScaffoldFile]s into a target directory. Existing files with the same name
 * are left untouched so a scaffold action never silently overwrites user edits.
 */
object DefoldScaffoldWriter {
    fun write(
        project: Project,
        directory: VirtualFile,
        files: List<ScaffoldFile>,
        openFirstFile: Boolean = true
    ): List<VirtualFile> {
        val created = mutableListOf<VirtualFile>()
        WriteCommandAction.runWriteCommandAction(project) {
            for (file in files) {
                if (directory.findChild(file.name) != null) continue
                val created0 = WriteAction.compute<VirtualFile, RuntimeException> {
                    directory.createChildData(this, file.name).apply {
                        setBinaryContent(file.content.toByteArray(Charsets.UTF_8))
                    }
                }
                created += created0
            }
        }
        if (openFirstFile) {
            created.firstOrNull()?.let { FileEditorManager.getInstance(project).openFile(it, true) }
        }
        return created
    }
}
