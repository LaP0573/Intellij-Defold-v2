package com.aridclown.intellij.defold.templates

import com.aridclown.intellij.defold.DefoldCoroutineService.Companion.launch
import com.aridclown.intellij.defold.DefoldProjectService.Companion.defoldProjectService
import com.aridclown.intellij.defold.actions.DefoldProjectAction
import com.aridclown.intellij.defold.templates.GameProjectAppManifest.GENERATED_APP_MANIFEST_FILE
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.ini4j.Config
import org.ini4j.Ini

/**
 * Generates `/generated.appmanifest` from a user-selected exclusion set and
 * wires it into `game.project` via `[native_extension] app_manifest`. Port
 * of Buddy's "Generate App Manifest" command (itself a port of
 * britzl/manifestation; mirrors the Defold editor's built-in generator).
 */
class GenerateManifestAction : DefoldProjectAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = BGT

    override fun actionPerformed(event: AnActionEvent) = withDefoldProject(event) { project ->
        val dialog = GenerateManifestDialog(project)
        if (!dialog.showAndGet()) return@withDefoldProject
        val config = dialog.config

        project.launch {
            writeManifestFile(project, config)
            updateGameProject(project)
        }
    }

    private suspend fun writeManifestFile(project: Project, config: AppManifestConfig) {
        val rootDir = project.defoldProjectService().gameProjectFile?.parent ?: return
        val content = AppManifestBuilder.build(config).toByteArray(Charsets.UTF_8)
        edtWriteAction {
            val target = rootDir.findChild(GENERATED_APP_MANIFEST_FILE)
                ?: rootDir.createChildData(this, GENERATED_APP_MANIFEST_FILE)
            target.setBinaryContent(content)
        }
    }

    private suspend fun updateGameProject(project: Project) {
        val gameProjectFile = project.defoldProjectService().gameProjectFile ?: return
        val ini = readIni(gameProjectFile)
        GameProjectAppManifest.apply(ini)
        writeIni(gameProjectFile, ini)
    }

    private fun readIni(file: VirtualFile): Ini = createIni().apply {
        file.contentsToByteArray().inputStream().use(::load)
    }

    private suspend fun writeIni(file: VirtualFile, ini: Ini) = edtWriteAction {
        file.getOutputStream(this).use(ini::store)
        file.refresh(false, false)
    }

    private fun createIni(): Ini = Ini().apply {
        config = Config().apply { isEscape = false }
    }
}
