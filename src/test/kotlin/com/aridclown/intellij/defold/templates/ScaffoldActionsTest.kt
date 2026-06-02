package com.aridclown.intellij.defold.templates

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ScaffoldActionsTest {
    @Nested
    inner class GameObjectScaffold {
        private val files = DefoldScaffold.gameObject(id = "player", directoryProjectPath = "scripts", includeFactory = true)
        private val byName = files.associateBy(ScaffoldFile::name)

        @Test
        fun `scaffolds the game object, script, and factory files for the given id`() {
            assertThat(files.map(ScaffoldFile::name))
                .containsExactly("player.script", "player.go", "player.factory")
        }

        @Test
        fun `game object textproto references the scaffolded script by project-relative path`() {
            val go = byName.getValue("player.go").content

            assertThat(go).contains("components {")
            assertThat(go).contains("id: \"player\"")
            assertThat(go).contains("component: \"/scripts/player.script\"")
        }

        @Test
        fun `game object embeds sprite and collisionobject components`() {
            val go = byName.getValue("player.go").content

            assertThat(go).contains("embedded_components {")
            assertThat(go).contains("type: \"sprite\"")
            assertThat(go).contains("type: \"collisionobject\"")
        }

        @Test
        fun `script carries an EmmyLua self class annotation and lifecycle stubs`() {
            val script = byName.getValue("player.script").content

            assertThat(script).startsWith("---@class player_self")
            assertThat(script).contains("function init(self)")
            assertThat(script).contains("function final(self)")
            assertThat(script).contains("function update(self, dt)")
            assertThat(script).contains("function on_message(self, message_id, message, sender)")
            assertThat(script).contains("function on_input(self, action_id, action)")
            assertThat(script).contains("function on_reload(self)")
        }

        @Test
        fun `factory references the scaffolded game object as its prototype`() {
            val factory = byName.getValue("player.factory").content

            assertThat(factory).contains("prototype: \"/scripts/player.go\"")
            assertThat(factory).contains("load_dynamically: false")
            assertThat(factory).contains("dynamic_prototype: false")
        }

        @Test
        fun `omitting the factory leaves only the game object and its script`() {
            val noFactory = DefoldScaffold.gameObject(id = "enemy", includeFactory = false)

            assertThat(noFactory.map(ScaffoldFile::name))
                .containsExactly("enemy.script", "enemy.go")
        }

        @Test
        fun `game object at the project root uses a leading-slash script reference`() {
            val atRoot = DefoldScaffold.gameObject(id = "boss", directoryProjectPath = "")
            val goContent = atRoot.first { it.name == "boss.go" }.content

            assertThat(goContent).contains("component: \"/boss.script\"")
        }
    }

    @Nested
    inner class GuiScaffold {
        private val files = DefoldScaffold.gui(id = "hud", directoryProjectPath = "gui", includeGuiScript = true)
        private val byName = files.associateBy(ScaffoldFile::name)

        @Test
        fun `scaffolds the gui scene and its gui_script`() {
            assertThat(files.map(ScaffoldFile::name))
                .containsExactly("hud.gui_script", "hud.gui")
        }

        @Test
        fun `gui scene references the scaffolded gui_script`() {
            val gui = byName.getValue("hud.gui").content

            assertThat(gui).contains("script: \"/gui/hud.gui_script\"")
        }

        @Test
        fun `gui_script carries an EmmyLua self class annotation and gui lifecycle stubs`() {
            val guiScript = byName.getValue("hud.gui_script").content

            assertThat(guiScript).startsWith("---@class hud_self")
            assertThat(guiScript).contains("function init(self)")
            assertThat(guiScript).contains("function update(self, dt)")
            assertThat(guiScript).contains("function on_message(self, message_id, message, sender)")
            assertThat(guiScript).contains("function on_input(self, action_id, action)")
        }

        @Test
        fun `omitting the gui_script leaves an empty script reference and only the gui scene`() {
            val sceneOnly = DefoldScaffold.gui(id = "menu", directoryProjectPath = "gui", includeGuiScript = false)

            assertThat(sceneOnly.map(ScaffoldFile::name))
                .containsExactly("menu.gui")
            assertThat(sceneOnly.single().content).contains("script: \"\"")
        }
    }

    @Nested
    inner class LuaModuleScaffold {
        @Test
        fun `scaffolds a single lua file with a module-named class annotation`() {
            val files = DefoldScaffold.luaModule("inventory")

            assertThat(files.map(ScaffoldFile::name))
                .containsExactly("inventory.lua")

            val module = files.single().content
            assertThat(module).startsWith("---@class inventory")
            assertThat(module).contains("local M = {}")
            assertThat(module).contains("return M")
        }
    }
}
