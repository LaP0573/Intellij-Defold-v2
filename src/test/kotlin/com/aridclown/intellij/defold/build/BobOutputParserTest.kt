package com.aridclown.intellij.defold.build

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.tuple
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class BobOutputParserTest {
    @Nested
    inner class ErrorLines {
        @Test
        fun `parses Bob-prefixed Lua compile error into structured problem`() {
            val problem = BobOutputParser.parseLine("ERROR: main/game.script:42: '=' expected near 'self'")

            assertThat(problem)
                .isNotNull
                .extracting({ it!!.file }, { it!!.line }, { it!!.column }, { it!!.severity }, { it!!.message })
                .containsExactly(
                    "main/game.script",
                    42,
                    null,
                    BobSeverity.ERROR,
                    "'=' expected near 'self'"
                )
        }

        @Test
        fun `parses bare luac-style error without severity prefix as ERROR`() {
            val problem = BobOutputParser.parseLine("main/scripts/util.lua:13: unexpected symbol near 'end'")

            assertThat(problem)
                .isNotNull
                .extracting({ it!!.file }, { it!!.line }, { it!!.severity })
                .containsExactly("main/scripts/util.lua", 13, BobSeverity.ERROR)
        }

        @Test
        fun `captures column when present in file location`() {
            val problem = BobOutputParser.parseLine("ERROR: main/gui/menu.gui_script:7:24: missing comma")

            assertThat(problem)
                .isNotNull
                .extracting({ it!!.line }, { it!!.column })
                .containsExactly(7, 24)
        }

        @Test
        fun `recognises FATAL and SEVERE as error severity`() {
            val fatal = BobOutputParser.parseLine("FATAL: main/game.script:1: catastrophic failure")
            val severe = BobOutputParser.parseLine("SEVERE: main/game.script:2: very bad")

            assertThat(listOf(fatal, severe))
                .allSatisfy { assertThat(it!!.severity).isEqualTo(BobSeverity.ERROR) }
        }
    }

    @Nested
    inner class WarningLines {
        @Test
        fun `parses Bob-prefixed warning`() {
            val problem = BobOutputParser.parseLine("WARNING: main/scripts/foo.lua:18: deprecated API")

            assertThat(problem)
                .isNotNull
                .extracting({ it!!.severity }, { it!!.line }, { it!!.message })
                .containsExactly(BobSeverity.WARNING, 18, "deprecated API")
        }

        @Test
        fun `parses short WARN prefix as warning`() {
            val problem = BobOutputParser.parseLine("WARN: main/foo.script:3: unused variable 'x'")

            assertThat(problem).isNotNull
            assertThat(problem!!.severity).isEqualTo(BobSeverity.WARNING)
        }
    }

    @Nested
    inner class IgnoredLines {
        @Test
        fun `ignores blank lines`() {
            assertThat(BobOutputParser.parseLine("")).isNull()
            assertThat(BobOutputParser.parseLine("    ")).isNull()
        }

        @Test
        fun `ignores plain progress chatter without a file location`() {
            val noise =
                listOf(
                    "Loading project...",
                    "Compiling 42 resources",
                    "[INFO] Building...",
                    "Bob version 1.10.4",
                    "Done in 1.2s"
                )

            assertThat(noise.mapNotNull(BobOutputParser::parseLine)).isEmpty()
        }

        @Test
        fun `ignores stack traceback lines without file colon line shape`() {
            val problem = BobOutputParser.parseLine("stack traceback:")

            assertThat(problem).isNull()
        }

        @Test
        fun `ignores lines with file but no line number`() {
            val problem = BobOutputParser.parseLine("ERROR: main/missing.atlas: image not found")

            assertThat(problem).isNull()
        }

        @Test
        fun `ignores lines whose line component is zero or negative`() {
            assertThat(BobOutputParser.parseLine("ERROR: main/game.script:0: bogus")).isNull()
        }
    }

    @Nested
    inner class BatchParsing {
        @Test
        fun `parse extracts only diagnostic lines from mixed Bob output`() {
            val output =
                """
                |Bob version 1.10.4
                |Building project...
                |ERROR: main/game.script:42: '=' expected near 'self'
                |WARNING: main/util.lua:7: deprecated API
                |Compiled 12 scripts
                |main/render.render_script:101: attempt to index nil value
                |
                |Build failed
                """.trimMargin()

            val problems = BobOutputParser.parse(output)

            assertThat(problems)
                .hasSize(3)
                .extracting({ it.file }, { it.line }, { it.severity })
                .containsExactly(
                    tuple("main/game.script", 42, BobSeverity.ERROR),
                    tuple("main/util.lua", 7, BobSeverity.WARNING),
                    tuple("main/render.render_script", 101, BobSeverity.ERROR)
                )
        }

        @Test
        fun `parse handles indented diagnostic lines emitted under Bob banners`() {
            val output =
                """
                |Building...
                |  ERROR: main/game.script:9: oops
                """.trimMargin()

            val problems = BobOutputParser.parse(output)

            assertThat(problems).hasSize(1)
            assertThat(problems.first().file).isEqualTo("main/game.script")
        }
    }
}
