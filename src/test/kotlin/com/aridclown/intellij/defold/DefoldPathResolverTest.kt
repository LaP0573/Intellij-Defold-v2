package com.aridclown.intellij.defold

import com.aridclown.intellij.defold.settings.DefoldSettings
import com.aridclown.intellij.defold.settings.DefoldSettingsConfigurable
import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class DefoldPathResolverTest {
    private val project = mockk<Project>(relaxed = true)
    private val application = mockk<Application>(relaxed = true)
    private val settings = mockk<DefoldSettings>(relaxed = true)
    private val notificationGroupManager = mockk<NotificationGroupManager>(relaxed = true)
    private val notificationGroup = mockk<NotificationGroup>(relaxed = true)
    private val notification = mockk<Notification>(relaxed = true)
    private val showSettingsUtil = mockk<ShowSettingsUtil>(relaxed = true)
    private val yesNoBuilder = mockk<MessageDialogBuilder.YesNo>(relaxed = true)
    private var capturedDialogTitle: String? = null
    private var capturedDialogMessage: String? = null
    private var capturedDialogYesText: String? = null
    private var capturedDialogNoText: String? = null

    @BeforeEach
    fun setUp() {
        // Mock ApplicationManager
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns application

        // Mock DefoldSettings
        mockkObject(DefoldSettings.Companion)
        every { DefoldSettings.getInstance() } returns settings

        // Mock Notifications
        mockkStatic(NotificationGroupManager::class)
        every { NotificationGroupManager.getInstance() } returns notificationGroupManager
        every { notificationGroupManager.getNotificationGroup("Defold") } returns notificationGroup
        every {
            notificationGroup.createNotification(
                any<String>(),
                any<String>(),
                any<NotificationType>()
            )
        } returns notification

        // Mock DefoldEditorConfig
        mockkObject(DefoldEditorConfig.Companion)

        // Mock Platform
        mockkObject(Platform.Companion)

        // Mock Messages for getCancelButton / getWarningIcon (no-op stubs are fine — they
        // are static accessors used to build the dialog, not blocking dialog calls).
        mockkStatic(Messages::class)
        every { Messages.getCancelButton() } returns "Cancel"
        every { Messages.getWarningIcon() } returns mockk(relaxed = true)

        // Mock MessageDialogBuilder.yesNo(...).icon(...).yesText(...).noText(...).ask(project)
        // chain. Each builder method captures its argument so verifyDialogShown can assert on
        // the prompt text / button labels that reached the dialog. Companion-object methods
        // are mocked via mockkObject(...Companion) — mockkStatic does not see @JvmStatic
        // companion methods reliably.
        mockkObject(MessageDialogBuilder.Companion)
        every { MessageDialogBuilder.yesNo(any(), any()) } answers {
            capturedDialogTitle = firstArg()
            capturedDialogMessage = secondArg()
            yesNoBuilder
        }
        every { yesNoBuilder.icon(any()) } returns yesNoBuilder
        every { yesNoBuilder.yesText(any()) } answers {
            capturedDialogYesText = firstArg()
            yesNoBuilder
        }
        every { yesNoBuilder.noText(any()) } answers {
            capturedDialogNoText = firstArg()
            yesNoBuilder
        }

        // Mock ShowSettingsUtil
        mockkStatic(ShowSettingsUtil::class)
        every { ShowSettingsUtil.getInstance() } returns showSettingsUtil
    }

    @AfterEach
    fun tearDown() {
        capturedDialogTitle = null
        capturedDialogMessage = null
        capturedDialogYesText = null
        capturedDialogNoText = null
        clearAllMocks()
        unmockkAll()
    }

    @Nested
    inner class ConfigLoading {
        @Test
        fun `returns config when valid`() {
            val expectedConfig = mockk<DefoldEditorConfig>()
            every { DefoldEditorConfig.loadEditorConfig() } returns expectedConfig

            val result = DefoldPathResolver.ensureEditorConfig(project)

            assertThat(result).isEqualTo(expectedConfig)
        }

        @Test
        fun `returns null when config invalid and user cancels`() {
            mockInvalidConfig(userClicksOk = false)

            val result = DefoldPathResolver.ensureEditorConfig(project)

            assertThat(result).isNull()
            verifySettingsNotOpened()
        }

        @Test
        fun `loads config after user fixes path in settings`() {
            val expectedConfig = mockk<DefoldEditorConfig>()
            var callCount = 0
            every { DefoldEditorConfig.loadEditorConfig() } answers {
                if (callCount++ == 0) null else expectedConfig
            }
            every { settings.installPath() } returns "/some/path"
            every { Platform.current() } returns Platform.MACOS
            every { yesNoBuilder.ask(any<Project>()) } returns true
            every {
                showSettingsUtil.showSettingsDialog(
                    any<Project>(),
                    eq(DefoldSettingsConfigurable::class.java)
                )
            } just Runs

            val result = DefoldPathResolver.ensureEditorConfig(project)

            assertThat(result).isEqualTo(expectedConfig)
            verifySettingsOpened()
            verify(exactly = 2) { DefoldEditorConfig.loadEditorConfig() }
        }
    }

    @Nested
    inner class DialogInteraction {
        @Test
        fun `shows dialog with attempted path when config missing`() {
            mockInvalidConfig(installPath = "/custom/path")

            DefoldPathResolver.ensureEditorConfig(project)

            verify(exactly = 1) { yesNoBuilder.ask(project) }
            assertThat(capturedDialogTitle).isEqualTo("Defold")
            assertThat(capturedDialogYesText).isEqualTo("Open Settings")
            assertThat(capturedDialogNoText).isEqualTo("Cancel")
            assertThat(capturedDialogMessage).isEqualTo(
                "The Defold installation path could not be located.\n" +
                    "Current location: /custom/path\n" +
                    "\n" +
                    "Would you like to update the path now?"
            )
        }

        @Test
        fun `shows dialog without path when none available`() {
            mockInvalidConfig(installPath = null, platform = Platform.UNKNOWN)

            DefoldPathResolver.ensureEditorConfig(project)

            verifyDialogShown {
                it.contains("The Defold installation path could not be located.") &&
                    !it.contains("Current location:") &&
                    it.contains("Would you like to update the path now?")
            }
        }

        @Test
        fun `opens settings when user clicks OK`() {
            mockInvalidConfig(userClicksOk = true)

            DefoldPathResolver.ensureEditorConfig(project)

            verifySettingsOpened()
            verify(exactly = 1) { notification.notify(any()) }
        }

        @Test
        fun `skips settings when user clicks Cancel`() {
            mockInvalidConfig(userClicksOk = false)

            DefoldPathResolver.ensureEditorConfig(project)

            verifySettingsNotOpened()
        }
    }

    @Nested
    inner class NotificationHandling {
        @Test
        fun `shows notification when config still invalid after settings`() {
            mockInvalidConfig(userClicksOk = true)

            val result = DefoldPathResolver.ensureEditorConfig(project)

            assertThat(result).isNull()
            verify(exactly = 2) { DefoldEditorConfig.loadEditorConfig() }
            verifySettingsOpened()
            verify(exactly = 1) { notification.addAction(any()) }
            verify(exactly = 1) { notification.notify(any()) }
        }

        @Test
        fun `notification has Configure action that reopens settings`() {
            mockInvalidConfig(userClicksOk = true)

            val result = DefoldPathResolver.ensureEditorConfig(project)

            assertThat(result).isNull()
            verify(exactly = 1) {
                notification.addAction(
                    match {
                        it.templateText == "Configure"
                    }
                )
            }
        }
    }

    @Nested
    inner class PlatformDetection {
        @Test
        fun `uses configured path when set`() {
            mockInvalidConfig(installPath = "/custom/defold")

            DefoldPathResolver.ensureEditorConfig(project)

            verifyDialogShown { it.contains("/custom/defold") }
        }

        @ParameterizedTest(name = "falls back to platform default for {0}")
        @CsvSource(
            "MACOS, /Applications/Defold.app",
            "WINDOWS, C:\\Program Files\\Defold",
            "LINUX, /usr/bin/Defold"
        )
        fun `falls back to platform default when path not configured`(
            platform: Platform,
            expectedPath: String
        ) {
            mockInvalidConfig(installPath = null, platform = platform)

            DefoldPathResolver.ensureEditorConfig(project)

            verifyDialogShown { it.contains(expectedPath) }
        }

        @Test
        fun `handles unknown platform gracefully`() {
            mockInvalidConfig(installPath = null, platform = Platform.UNKNOWN)

            val result = DefoldPathResolver.ensureEditorConfig(project)

            assertThat(result).isNull()
        }
    }

    private fun mockInvalidConfig(
        installPath: String? = "/some/path",
        platform: Platform = Platform.MACOS,
        userClicksOk: Boolean = false
    ) {
        every { DefoldEditorConfig.loadEditorConfig() } returns null
        every { settings.installPath() } returns installPath
        every { Platform.current() } returns platform
        every { yesNoBuilder.ask(any<Project>()) } returns userClicksOk
        every {
            showSettingsUtil.showSettingsDialog(
                any<Project>(),
                eq(DefoldSettingsConfigurable::class.java)
            )
        } just Runs
    }

    private fun verifyDialogShown(messageMatcher: (String) -> Boolean) {
        verify(exactly = 1) { yesNoBuilder.ask(any<Project>()) }
        val message = capturedDialogMessage
        assertThat(message).isNotNull()
        assertThat(messageMatcher(message!!)).isTrue()
    }

    private fun verifySettingsNotOpened() {
        verify(exactly = 0) {
            showSettingsUtil.showSettingsDialog(
                any<Project>(),
                any<Class<DefoldSettingsConfigurable>>()
            )
        }
    }

    private fun verifySettingsOpened() {
        verify(exactly = 1) {
            showSettingsUtil.showSettingsDialog(project, DefoldSettingsConfigurable::class.java)
        }
    }
}
