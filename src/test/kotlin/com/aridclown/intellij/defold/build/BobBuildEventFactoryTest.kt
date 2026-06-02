package com.aridclown.intellij.defold.build

import com.intellij.build.events.MessageEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File

class BobBuildEventFactoryTest {
    private val buildId = Any()

    @Nested
    inner class FileMessageEventShape {
        @Test
        fun `error problem produces ERROR kind, line-1 start line, exact message`() {
            val problem =
                BobProblem(
                    file = "main/game.script",
                    line = 42,
                    column = null,
                    severity = BobSeverity.ERROR,
                    message = "'=' expected near 'self'"
                )

            val event = BobBuildEventFactory.toEvent(buildId, problem, projectBasePath = "/proj")

            assertThat(event.kind).isEqualTo(MessageEvent.Kind.ERROR)
            assertThat(event.group).isEqualTo(BobBuildEventFactory.GROUP)
            assertThat(event.message).isEqualTo("'=' expected near 'self'")
            assertThat(event.filePosition.startLine).isEqualTo(41)
            assertThat(event.filePosition.startColumn).isEqualTo(0)
            assertThat(event.filePosition.file).isEqualTo(File("/proj", "main/game.script"))
        }

        @Test
        fun `warning problem maps to WARNING kind`() {
            val problem =
                BobProblem(
                    file = "main/util.lua",
                    line = 7,
                    column = null,
                    severity = BobSeverity.WARNING,
                    message = "deprecated API"
                )

            val event = BobBuildEventFactory.toEvent(buildId, problem, projectBasePath = "/proj")

            assertThat(event.kind).isEqualTo(MessageEvent.Kind.WARNING)
            assertThat(event.message).isEqualTo("deprecated API")
        }

        @Test
        fun `info problem maps to INFO kind`() {
            val problem =
                BobProblem(
                    file = "main/x.lua",
                    line = 1,
                    column = null,
                    severity = BobSeverity.INFO,
                    message = "note"
                )

            val event = BobBuildEventFactory.toEvent(buildId, problem, projectBasePath = "/proj")

            assertThat(event.kind).isEqualTo(MessageEvent.Kind.INFO)
        }

        @Test
        fun `column when present maps to startColumn minus one`() {
            val problem =
                BobProblem(
                    file = "main/gui/menu.gui_script",
                    line = 7,
                    column = 24,
                    severity = BobSeverity.ERROR,
                    message = "missing comma"
                )

            val event = BobBuildEventFactory.toEvent(buildId, problem, projectBasePath = "/proj")

            assertThat(event.filePosition.startLine).isEqualTo(6)
            assertThat(event.filePosition.startColumn).isEqualTo(23)
        }

        @Test
        fun `absolute paths in problem are not joined with project base`() {
            val problem =
                BobProblem(
                    file = "/abs/path/main/game.script",
                    line = 3,
                    column = null,
                    severity = BobSeverity.ERROR,
                    message = "boom"
                )

            val event = BobBuildEventFactory.toEvent(buildId, problem, projectBasePath = "/proj")

            assertThat(event.filePosition.file).isEqualTo(File("/abs/path/main/game.script"))
        }

        @Test
        fun `null project base path keeps file relative`() {
            val problem =
                BobProblem(
                    file = "main/game.script",
                    line = 9,
                    column = null,
                    severity = BobSeverity.ERROR,
                    message = "boom"
                )

            val event = BobBuildEventFactory.toEvent(buildId, problem, projectBasePath = null)

            assertThat(event.filePosition.file).isEqualTo(File("main/game.script"))
        }

        @Test
        fun `propagates buildId so the Build view groups events under the same session`() {
            val problem =
                BobProblem(
                    file = "main/game.script",
                    line = 1,
                    column = null,
                    severity = BobSeverity.ERROR,
                    message = "x"
                )

            val event = BobBuildEventFactory.toEvent(buildId, problem, projectBasePath = "/proj")

            assertThat(event.parentId).isSameAs(buildId)
        }
    }

    @Nested
    inner class EdgeCases {
        @Test
        fun `line 1 maps to startLine 0`() {
            val problem =
                BobProblem(
                    file = "main/x.lua",
                    line = 1,
                    column = 1,
                    severity = BobSeverity.ERROR,
                    message = "first-line error"
                )

            val event = BobBuildEventFactory.toEvent(buildId, problem, projectBasePath = "/proj")

            assertThat(event.filePosition.startLine).isZero()
            assertThat(event.filePosition.startColumn).isZero()
        }

        @Test
        fun `parsed-then-emitted roundtrip preserves Bob output payload`() {
            val parsed =
                BobOutputParser.parseLine(
                    "ERROR: main/game.script:42:7: '=' expected near 'self'"
                )

            requireNotNull(parsed) { "fixture line must parse" }
            val event = BobBuildEventFactory.toEvent(buildId, parsed, projectBasePath = "/proj")

            assertThat(event.kind).isEqualTo(MessageEvent.Kind.ERROR)
            assertThat(event.filePosition.startLine).isEqualTo(41)
            assertThat(event.filePosition.startColumn).isEqualTo(6)
            assertThat(event.message).isEqualTo("'=' expected near 'self'")
            assertThat(event.group).isEqualTo(BobBuildEventFactory.GROUP)
        }
    }
}
