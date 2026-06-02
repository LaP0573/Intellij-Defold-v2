package com.aridclown.intellij.defold.build

import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.events.impl.FailureResultImpl
import com.intellij.build.events.impl.FileMessageEventImpl
import com.intellij.build.events.impl.FinishBuildEventImpl
import com.intellij.build.events.impl.StartBuildEventImpl
import com.intellij.build.events.impl.SuccessResultImpl
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level.PROJECT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicReference

/**
 * Surfaces [BobProblem] entries produced by [BobOutputParser] in the IDE Build tool window using
 * the platform Build API. Each problem becomes a [FileMessageEventImpl] carrying
 * file:line:severity:message, so the Build view shows native, double-click-to-navigate entries
 * grouped under a "Defold Bob" node — the same surface Gradle/Maven errors use.
 *
 * Lifecycle: a Bob run calls [startRun] to open a build session, [report] for every parsed problem
 * (which streams a file-position-carrying message event into the view), and [finishRun] when the
 * process terminates so the view marks the session done.
 */
@Service(PROJECT)
class BobProblemReporter(
    private val project: Project
) {
    private val currentBuildId = AtomicReference<Any?>(null)
    private val sawFailure = AtomicReference(false)

    /**
     * Open a fresh build session in the Build tool window. Stale problems from any previous run on
     * the same session are dropped when the new [StartBuildEventImpl] is dispatched.
     */
    fun startRun(buildTitle: String = DEFAULT_TITLE): Any {
        val buildId = Any()
        currentBuildId.set(buildId)
        sawFailure.set(false)
        val workingDir = project.basePath ?: ""
        val descriptor =
            DefaultBuildDescriptor(buildId, buildTitle, workingDir, System.currentTimeMillis())
        viewManager().onEvent(buildId, StartBuildEventImpl(descriptor, buildTitle))
        return buildId
    }

    /**
     * Stream a single [BobProblem] into the active build session as a structured
     * file:line:severity:message event. Returns the dispatched event for callers that want to
     * inspect (or test) its payload; returns `null` if no build session is open.
     */
    fun report(problem: BobProblem): FileMessageEventImpl? {
        val buildId = currentBuildId.get() ?: return null
        val event = BobBuildEventFactory.toEvent(buildId, problem, project.basePath)
        if (problem.severity == BobSeverity.ERROR) sawFailure.set(true)
        viewManager().onEvent(buildId, event)
        return event
    }

    fun report(problems: Iterable<BobProblem>): List<FileMessageEventImpl> = problems.mapNotNull(::report)

    /**
     * Close the active build session. [success] indicates the Bob process exit status — independent
     * of whether parser-level errors were reported — and selects a success/failure result so the
     * Build view renders the appropriate terminal node icon.
     */
    fun finishRun(success: Boolean = true) {
        val buildId = currentBuildId.getAndSet(null) ?: return
        val result = if (success && !sawFailure.get()) SuccessResultImpl() else FailureResultImpl()
        viewManager().onEvent(
            buildId,
            FinishBuildEventImpl(
                buildId,
                null,
                System.currentTimeMillis(),
                if (success) "completed" else "failed",
                result
            )
        )
    }

    private fun viewManager(): BuildViewManager = project.service<BuildViewManager>()

    companion object {
        const val DEFAULT_TITLE: String = "Defold Bob"

        fun Project.bobProblemReporter(): BobProblemReporter = service<BobProblemReporter>()

        /**
         * Lazy variant that returns `null` instead of throwing when the service is not registered
         * (e.g. in unit tests that mock the [Project] without a live service container).
         */
        fun Project.bobProblemReporterOrNull(): BobProblemReporter? = runCatching { bobProblemReporter() }.getOrNull()
    }
}
