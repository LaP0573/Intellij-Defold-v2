package com.aridclown.intellij.defold.completion

import com.aridclown.intellij.defold.resources.DefoldUrlIndexService.Companion.defoldUrlIndexService
import com.intellij.codeInsight.completion.CompletionContributorEP
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.assertj.core.api.Assertions.assertThat

class DefoldUrlCompletionContributorTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        disableLspCompletionContributor()
        associateScriptExtensionsWithLua()
    }

    fun `test completion offers instance and component URLs for script attached to host go`() {
        seedDefoldFixture()

        myFixture.configureByText(
            "main.script",
            """
            local url = "<caret>"
            """.trimIndent()
        )

        project.defoldUrlIndexService().invalidate()

        myFixture.completeBasic()

        assertThat(myFixture.lookupElementStrings ?: emptyList())
            .contains("/enemy", "/enemy#sprite", "#componentId")
    }

    fun `test completion in lua module offers all instance URLs across collections`() {
        seedDefoldFixture()

        myFixture.configureByText(
            "shared.lua",
            """
            local url = "<caret>"
            """.trimIndent()
        )

        project.defoldUrlIndexService().invalidate()

        myFixture.completeBasic()

        assertThat(myFixture.lookupElementStrings ?: emptyList())
            .contains("/enemy", "/enemy#sprite", "/host", "/host#componentId")
    }

    fun `test completion is suppressed inside require call`() {
        seedDefoldFixture()

        myFixture.configureByText(
            "module.lua",
            """
            local mod = require("<caret>")
            """.trimIndent()
        )

        project.defoldUrlIndexService().invalidate()

        myFixture.completeBasic()

        assertThat(myFixture.lookupElementStrings ?: emptyList())
            .doesNotContain("/enemy", "/host", "/enemy#sprite")
    }

    private fun seedDefoldFixture() {
        myFixture.addFileToProject("game.project", "[project]\ntitle = test\n")
        myFixture.addFileToProject(
            "host.go",
            """
            components {
              id: "componentId"
              component: "/main.script"
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "main.collection",
            """
            name: "main"
            instances {
              id: "host"
              prototype: "/host.go"
            }
            embedded_instances {
              id: "enemy"
              data: "embedded_components {\n  id: \"sprite\"\n  type: \"sprite\"\n}\n"
            }
            """.trimIndent()
        )
    }

    private fun associateScriptExtensionsWithLua() {
        val fileTypeManager = FileTypeManager.getInstance()
        val luaType: FileType = fileTypeManager.getFileTypeByExtension("lua")
        WriteAction.runAndWait<RuntimeException> {
            listOf("*.script", "*.gui_script", "*.render_script", "*.editor_script").forEach { pattern ->
                fileTypeManager.associatePattern(luaType, pattern)
            }
        }
    }

    private fun disableLspCompletionContributor() {
        val contributors = COMPLETION_EP_NAME.extensionList
        val filtered = contributors.filterNot { it.pluginDescriptor.pluginId.idString == LSP_PLUGIN_ID }
        if (filtered.size == contributors.size) return
        ExtensionTestUtil.maskExtensions(COMPLETION_EP_NAME, filtered.toMutableList(), testRootDisposable)
    }

    private companion object {
        const val LSP_PLUGIN_ID = "com.redhat.devtools.lsp4ij"
        val COMPLETION_EP_NAME: ExtensionPointName<CompletionContributorEP> =
            ExtensionPointName.create("com.intellij.completion.contributor")
    }
}
