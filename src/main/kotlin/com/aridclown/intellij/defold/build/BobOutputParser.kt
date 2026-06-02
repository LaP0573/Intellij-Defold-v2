package com.aridclown.intellij.defold.build

/**
 * Parses Bob/Lua compile output lines into structured problems for the IDE Problems view.
 *
 * Bob (the Defold build tool) and the embedded Lua compiler emit diagnostics in a handful of
 * loosely standardised shapes. This parser recognises the common ones and discards lines that
 * carry no `file:line` location (progress chatter, banners, stack traces without anchors).
 */
object BobOutputParser {
    private val pattern =
        Regex(
            buildString {
                append("^") // anchor at start of line (post-trim)
                append("(?:(ERROR|WARNING|FATAL|SEVERE|INFO|WARN):\\s+)?") // optional severity prefix
                append("([^\\s:]+\\.[A-Za-z0-9_]+)") // file path: non-space token ending in .ext
                append(":(\\d+)") // line number
                append("(?::(\\d+))?") // optional column number
                append(":\\s*") // separator
                append("(.+?)") // message
                append("\\s*$") // trailing whitespace
            }
        )

    fun parseLine(rawLine: String): BobProblem? {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) return null

        val match = pattern.matchEntire(trimmed) ?: return null
        val (severityToken, file, line, column, message) = match.destructured

        val severity = severityFromToken(severityToken, message)
        val lineNumber = line.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val columnNumber = column.toIntOrNull()?.takeIf { it > 0 }

        return BobProblem(
            file = file,
            line = lineNumber,
            column = columnNumber,
            severity = severity,
            message = message.trim()
        )
    }

    fun parse(lines: Sequence<String>): List<BobProblem> = lines.mapNotNull(::parseLine).toList()

    fun parse(text: String): List<BobProblem> = parse(text.lineSequence())

    private fun severityFromToken(
        token: String,
        message: String
    ): BobSeverity {
        if (token.isNotEmpty()) {
            return when (token.uppercase()) {
                "ERROR", "FATAL", "SEVERE" -> BobSeverity.ERROR
                "WARNING", "WARN" -> BobSeverity.WARNING
                else -> BobSeverity.INFO
            }
        }

        // No explicit prefix: infer from the message itself when it carries one.
        val lower = message.lowercase()
        return when {
            lower.startsWith("warning") -> BobSeverity.WARNING
            lower.startsWith("error") -> BobSeverity.ERROR
            else -> BobSeverity.ERROR // bare `file:line: msg` from luac is conventionally an error
        }
    }
}

enum class BobSeverity { ERROR, WARNING, INFO }

data class BobProblem(
    val file: String,
    val line: Int,
    val column: Int?,
    val severity: BobSeverity,
    val message: String
)
