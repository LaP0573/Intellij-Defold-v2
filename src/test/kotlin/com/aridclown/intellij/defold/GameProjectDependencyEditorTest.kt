package com.aridclown.intellij.defold

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GameProjectDependencyEditorTest {
    private val fixtureWithNoDependencies =
        """
        [project]
        title = my game
        version = 0.1

        [bootstrap]
        main_collection = /main/main.collectionc

        [display]
        width = 960
        height = 640
        """.trimIndent()

    private val fixtureWithOneDependency =
        """
        [project]
        title = my game
        version = 0.1
        dependencies#0 = https://github.com/defold/extension-iac/archive/refs/tags/1.2.3.zip

        [bootstrap]
        main_collection = /main/main.collectionc
        """.trimIndent()

    @Test
    fun `lists dependencies in index order`() {
        val content =
            """
            [project]
            title = my game
            dependencies#0 = https://example.com/a.zip
            dependencies#1 = https://example.com/b.zip
            dependencies#2 = https://example.com/c.zip
            """.trimIndent()

        assertThat(GameProjectDependencyEditor.listDependencies(content))
            .containsExactly(
                "https://example.com/a.zip",
                "https://example.com/b.zip",
                "https://example.com/c.zip"
            )
    }

    @Test
    fun `returns empty list when there are no dependency entries`() {
        assertThat(GameProjectDependencyEditor.listDependencies(fixtureWithNoDependencies)).isEmpty()
    }

    @Test
    fun `appends a new dependency at the next free index`() {
        val updated =
            GameProjectDependencyEditor.appendDependency(
                fixtureWithOneDependency,
                "https://github.com/foo/bar/archive/refs/heads/main.zip"
            )

        assertThat(GameProjectDependencyEditor.listDependencies(updated))
            .containsExactly(
                "https://github.com/defold/extension-iac/archive/refs/tags/1.2.3.zip",
                "https://github.com/foo/bar/archive/refs/heads/main.zip"
            )
        assertThat(updated).contains("dependencies#1 = https://github.com/foo/bar/archive/refs/heads/main.zip")
    }

    @Test
    fun `appends to an empty project section when no dependencies exist yet`() {
        val updated =
            GameProjectDependencyEditor.appendDependency(
                fixtureWithNoDependencies,
                "https://github.com/foo/bar/archive/main.zip"
            )

        assertThat(GameProjectDependencyEditor.listDependencies(updated))
            .containsExactly("https://github.com/foo/bar/archive/main.zip")
        assertThat(updated).contains("dependencies#0 = https://github.com/foo/bar/archive/main.zip")
    }

    @Test
    fun `appending an already-present url is a no-op`() {
        val url = "https://github.com/defold/extension-iac/archive/refs/tags/1.2.3.zip"

        val updated = GameProjectDependencyEditor.appendDependency(fixtureWithOneDependency, url)

        assertThat(updated).isEqualTo(fixtureWithOneDependency)
        assertThat(GameProjectDependencyEditor.listDependencies(updated)).containsExactly(url)
    }

    @Test
    fun `preserves unrelated keys and sections when appending`() {
        val updated =
            GameProjectDependencyEditor.appendDependency(
                fixtureWithOneDependency,
                "https://github.com/foo/bar/archive/main.zip"
            )

        assertThat(updated)
            .contains("title = my game")
            .contains("version = 0.1")
            .contains("[bootstrap]")
            .contains("main_collection = /main/main.collectionc")
    }

    @Test
    fun `creates the project section if it is missing`() {
        val empty = "[display]\nwidth = 960\nheight = 640\n"

        val updated =
            GameProjectDependencyEditor.appendDependency(
                empty,
                "https://example.com/foo.zip"
            )

        assertThat(updated)
            .contains("[project]")
            .contains("dependencies#0 = https://example.com/foo.zip")
            .contains("[display]")
            .contains("width = 960")
    }

    @Test
    fun `setDependency replaces the value at the given index`() {
        val updated =
            GameProjectDependencyEditor.setDependency(
                fixtureWithOneDependency,
                index = 0,
                dependencyUrl = "https://example.com/replacement.zip"
            )

        assertThat(GameProjectDependencyEditor.listDependencies(updated))
            .containsExactly("https://example.com/replacement.zip")
    }

    @Test
    fun `setDependency appends at the requested index when previously absent`() {
        val updated =
            GameProjectDependencyEditor.setDependency(
                fixtureWithOneDependency,
                index = 1,
                dependencyUrl = "https://example.com/second.zip"
            )

        assertThat(GameProjectDependencyEditor.listDependencies(updated))
            .containsExactly(
                "https://github.com/defold/extension-iac/archive/refs/tags/1.2.3.zip",
                "https://example.com/second.zip"
            )
    }

    @Test
    fun `appendDependency rejects blank urls`() {
        assertThatIllegalArgumentException {
            GameProjectDependencyEditor.appendDependency(fixtureWithNoDependencies, " ")
        }
    }

    @Test
    fun `setDependency rejects negative indices`() {
        assertThatIllegalArgumentException {
            GameProjectDependencyEditor.setDependency(
                fixtureWithNoDependencies,
                index = -1,
                dependencyUrl = "https://example.com/foo.zip"
            )
        }
    }

    private fun assertThatIllegalArgumentException(block: () -> Unit) {
        org.assertj.core.api.Assertions
            .assertThatThrownBy { block() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
