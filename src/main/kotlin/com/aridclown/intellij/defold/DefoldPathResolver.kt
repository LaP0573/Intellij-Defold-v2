package com.aridclown.intellij.defold

import com.aridclown.intellij.defold.settings.DefoldSettings
import com.aridclown.intellij.defold.settings.DefoldSettingsConfigurable
import com.aridclown.intellij.defold.util.NotificationService.notify
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType.ERROR
import com.intellij.notification.NotificationType.WARNING
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.util.concurrency.annotations.RequiresEdt

object DefoldPathResolver {
    /**
     * Startup-safe, **non-blocking** resolver. Returns the editor config if the Defold install
     * path is already configured; otherwise posts a **non-modal** notification (a balloon with a
     * "Configure" action that opens settings) and returns `null`. It never opens a modal dialog
     * and never touches the EDT, so it is safe to call from project startup
     * ([DefoldProjectActivity]) — on any thread — without blocking the EDT.
     *
     * Post-startup, user-initiated callers that need an answer inline (run/debug, menu actions)
     * should use [ensureEditorConfig], which prompts with a modal dialog.
     */
    fun ensureEditorConfigOrNotify(project: Project): DefoldEditorConfig? {
        DefoldEditorConfig.loadEditorConfig()?.let { return it }
        notifyConfigMissing(project)
        return null
    }

    /**
     * Prompts for the Defold install path with a **modal** dialog when it cannot be located, then
     * returns the resolved config (`null` if the user cancels or the path is still invalid).
     *
     * `@RequiresEdt` because [MessageDialogBuilder.YesNo.ask] and
     * [ShowSettingsUtil.showSettingsDialog] are EDT-only and block the EDT while open. Call this
     * **only** from a post-startup, user-initiated action (run/debug, menu actions) where a modal
     * prompt is expected — never from project startup, which must not block the EDT (use
     * [ensureEditorConfigOrNotify] there).
     */
    @RequiresEdt
    fun ensureEditorConfig(project: Project): DefoldEditorConfig? {
        val attemptedPath = effectiveInstallPath()
        DefoldEditorConfig.loadEditorConfig()?.let { return it }

        val message = buildString {
            append("The Defold installation path could not be located.")
            attemptedPath?.let {
                append('\n')
                append("Current location: ")
                append(it)
            }
            append("\n\nWould you like to update the path now?")
        }

        val openSettings = MessageDialogBuilder
            .yesNo("Defold", message)
            .icon(Messages.getWarningIcon())
            .yesText("Open Settings")
            .noText(Messages.getCancelButton())
            .ask(project)

        if (!openSettings) return null

        ShowSettingsUtil.getInstance().showSettingsDialog(project, DefoldSettingsConfigurable::class.java)

        val config = DefoldEditorConfig.loadEditorConfig()

        if (config == null) {
            project.notify(
                title = "Invalid Defold editor path",
                content =
                buildString {
                    append("The Defold installation path could not be located. ")
                    append("Please ensure Defold is installed and the path is configured correctly.")
                },
                type = ERROR,
                expireOnActionClick = true,
                actions =
                listOf(
                    NotificationAction.createSimple("Configure") {
                        ShowSettingsUtil
                            .getInstance()
                            .showSettingsDialog(project, DefoldSettingsConfigurable::class.java)
                    }
                )
            )
            return null
        }

        return config
    }

    /**
     * Posts a non-modal balloon prompting the user to configure the Defold install path, with a
     * "Configure" action that opens the Defold settings page. Safe to call from any thread — it
     * never blocks the EDT (the modal-free counterpart to the dialog in [ensureEditorConfig]).
     */
    private fun notifyConfigMissing(project: Project) {
        val attemptedPath = effectiveInstallPath()
        project.notify(
            title = "Defold installation path not configured",
            content =
            buildString {
                append("The Defold installation path could not be located.")
                attemptedPath?.let {
                    append(" Current location: ")
                    append(it)
                    append('.')
                }
                append(" Configure it to enable dependency resolution.")
            },
            type = WARNING,
            expireOnActionClick = true,
            actions =
            listOf(
                NotificationAction.createSimple("Configure") {
                    ShowSettingsUtil
                        .getInstance()
                        .showSettingsDialog(project, DefoldSettingsConfigurable::class.java)
                }
            )
        )
    }

    private fun effectiveInstallPath(): String? {
        val settings = DefoldSettings.getInstance()
        val platform = Platform.current()
        return settings.installPath() ?: DefoldDefaults.installPathSuggestion(platform)
    }
}
