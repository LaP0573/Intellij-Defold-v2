package com.aridclown.intellij.defold.build

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.util.Key

/**
 * Streams Bob's stdout/stderr through [BobOutputParser] and forwards parsed problems to a
 * [BobProblemReporter]. Lines that don't match a Bob diagnostic pattern are ignored — the regular
 * console output handling still surfaces them as plain text via the existing log pipeline.
 *
 * The reporter is resolved lazily so unit tests that mock the surrounding [com.intellij.openapi.project.Project]
 * don't have to wire the service container — when no reporter is available, parsed problems are
 * silently dropped and Bob output remains plain console text.
 */
class BobProblemProcessListener(
    private val buildTitle: String,
    private val reporterProvider: () -> BobProblemReporter?
) : ProcessListener {
    private val buffer = StringBuilder()
    private var started = false

    override fun startNotified(event: ProcessEvent) {
        val reporter = reporterProvider() ?: return
        reporter.startRun(buildTitle)
        started = true
    }

    override fun onTextAvailable(
        event: ProcessEvent,
        outputType: Key<*>
    ) {
        buffer.append(event.text)
        while (true) {
            val newlineIdx = buffer.indexOf('\n')
            if (newlineIdx == -1) break
            val line = buffer.substring(0, newlineIdx)
            buffer.delete(0, newlineIdx + 1)
            handleLine(line)
        }
    }

    override fun processTerminated(event: ProcessEvent) {
        if (buffer.isNotEmpty()) {
            handleLine(buffer.toString())
            buffer.setLength(0)
        }
        if (started) {
            reporterProvider()?.finishRun(success = event.exitCode == 0)
            started = false
        }
    }

    private fun handleLine(line: String) {
        val problem = BobOutputParser.parseLine(line) ?: return
        reporterProvider()?.report(problem)
    }
}
