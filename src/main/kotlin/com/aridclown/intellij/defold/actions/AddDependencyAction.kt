package com.aridclown.intellij.defold.actions

import com.aridclown.intellij.defold.DefoldCoroutineService.Companion.launch
import com.aridclown.intellij.defold.DefoldEditorConfig
import com.aridclown.intellij.defold.DefoldProjectService.Companion.defoldProjectService
import com.aridclown.intellij.defold.DependencyResolver
import com.aridclown.intellij.defold.GameProjectDependencyEditor
import com.aridclown.intellij.defold.GitHubReleasesService
import com.aridclown.intellij.defold.GitHubReleasesService.RepoRef
import com.aridclown.intellij.defold.GitHubReleasesService.SelectableRef
import com.aridclown.intellij.defold.util.NotificationService.notifyError
import com.aridclown.intellij.defold.util.NotificationService.notifyInfo
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TITLE = "Add Defold Dependency"

class AddDependencyAction : DefoldProjectAction() {
    override fun actionPerformed(event: AnActionEvent) = withDefoldProject(event) { project ->
        withDefoldConfig(project) { config ->
            val repoUrl = promptForRepoUrl(project) ?: return@withDefoldConfig

            val repoRef = GitHubReleasesService.parseRepoUrl(repoUrl)
            if (repoRef == null) {
                project.notifyError(TITLE, "Not a valid GitHub repository URL: $repoUrl")
                return@withDefoldConfig
            }

            project.launch {
                handleAdd(project, config, repoRef)
            }
        }
    }

    private fun promptForRepoUrl(project: Project): String? = Messages
        .showInputDialog(
            project,
            "Enter the GitHub repository URL of the Defold library (e.g. https://github.com/owner/repo):",
            TITLE,
            Messages.getQuestionIcon()
        )?.trim()
        ?.takeIf { it.isNotBlank() }

    private suspend fun handleAdd(
        project: Project,
        config: DefoldEditorConfig,
        repoRef: RepoRef
    ) {
        val refs = try {
            GitHubReleasesService.listRefs(repoRef)
        } catch (e: Exception) {
            project.notifyError(TITLE, "Failed to query GitHub: ${e.message ?: e.javaClass.simpleName}")
            return
        }

        if (refs.isEmpty()) {
            project.notifyError(
                TITLE,
                "No releases or default branch found for ${repoRef.owner}/${repoRef.repo}."
            )
            return
        }

        val selected = withContext(Dispatchers.EDT) { chooseRef(project, repoRef, refs) } ?: return

        val gameProjectFile = project.defoldProjectService().gameProjectFile
        if (gameProjectFile == null) {
            project.notifyError(TITLE, "game.project was not found in this project.")
            return
        }

        val changed = writeDependency(gameProjectFile, selected.zipUrl)
        if (!changed) {
            project.notifyInfo(
                TITLE,
                "${selected.zipUrl} is already declared in game.project; resolving anyway."
            )
        } else {
            project.notifyInfo(TITLE, "Added ${selected.label}. Resolving dependencies...")
        }

        DependencyResolver.resolve(project, config)
    }

    private fun chooseRef(
        project: Project,
        repoRef: RepoRef,
        refs: List<SelectableRef>
    ): SelectableRef? {
        if (refs.size == 1) {
            val only = refs.single()
            val confirmed = MessageDialogBuilder
                .yesNo(TITLE, "Add ${only.label} from ${repoRef.owner}/${repoRef.repo}?")
                .ask(project)
            return if (confirmed) only else null
        }

        val labels = refs.map { it.label }.toTypedArray()
        val choiceIndex = Messages.showChooseDialog(
            project,
            "Pick a version to add from ${repoRef.owner}/${repoRef.repo}:",
            TITLE,
            Messages.getQuestionIcon(),
            labels,
            labels.first()
        )
        return refs.getOrNull(choiceIndex)
    }

    private suspend fun writeDependency(
        gameProjectFile: VirtualFile,
        dependencyUrl: String
    ): Boolean {
        val original = readAction {
            String(gameProjectFile.contentsToByteArray(), Charsets.UTF_8)
        }
        val updated = GameProjectDependencyEditor.appendDependency(original, dependencyUrl)
        if (updated == original) return false

        edtWriteAction {
            gameProjectFile.setBinaryContent(updated.toByteArray(Charsets.UTF_8))
            gameProjectFile.refresh(false, false)
        }
        return true
    }
}
