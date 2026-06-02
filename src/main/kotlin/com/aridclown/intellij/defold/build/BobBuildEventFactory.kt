package com.aridclown.intellij.defold.build

import com.intellij.build.FilePosition
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.impl.FileMessageEventImpl
import java.io.File

/**
 * Pure converter from [BobProblem] to the platform [FileMessageEventImpl] / [FilePosition] pair the
 * Build tool window consumes. Splitting this out keeps the conversion logic unit-testable without
 * needing an IntelliJ platform fixture — the resulting events expose `getFilePosition()`,
 * `getKind()`, `getGroup()`, `getMessage()` for direct assertion.
 *
 * The platform uses 0-based line/column indexing (see [FilePosition]); Bob/Lua diagnostics are
 * 1-based, so the factory subtracts 1 on the way in. Bob does not always carry a column — when
 * absent we report column 0 (the start of the line).
 */
object BobBuildEventFactory {
    const val GROUP: String = "Defold Bob"

    fun toFilePosition(
        problem: BobProblem,
        projectBasePath: String?
    ): FilePosition {
        val file =
            when {
                projectBasePath.isNullOrBlank() -> File(problem.file)
                File(problem.file).isAbsolute -> File(problem.file)
                else -> File(projectBasePath, problem.file)
            }
        val startLine = (problem.line - 1).coerceAtLeast(0)
        val startColumn = ((problem.column ?: 1) - 1).coerceAtLeast(0)
        return FilePosition(file, startLine, startColumn)
    }

    fun toKind(severity: BobSeverity): MessageEvent.Kind = when (severity) {
        BobSeverity.ERROR -> MessageEvent.Kind.ERROR
        BobSeverity.WARNING -> MessageEvent.Kind.WARNING
        BobSeverity.INFO -> MessageEvent.Kind.INFO
    }

    fun toEvent(
        buildId: Any,
        problem: BobProblem,
        projectBasePath: String?
    ): FileMessageEventImpl {
        val kind = toKind(problem.severity)
        val filePosition = toFilePosition(problem, projectBasePath)
        return FileMessageEventImpl(
            buildId,
            kind,
            GROUP,
            problem.message,
            problem.message,
            filePosition
        )
    }
}
