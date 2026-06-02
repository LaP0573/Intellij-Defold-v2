package com.aridclown.intellij.defold.build

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level.PROJECT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.problems.WolfTheProblemSolver
import java.util.concurrent.ConcurrentHashMap

/**
 * Surfaces [BobProblem] entries produced by [BobOutputParser] in the IDE Problems tool window.
 *
 * Uses the platform [WolfTheProblemSolver] external-source API so files Bob flagged appear in the
 * Problems view alongside inspection results — clicking an entry opens the file at the parsed line
 * via the editor's standard file navigation.
 */
@Service(PROJECT)
class BobProblemReporter(
    private val project: Project
) {
    private val source: Any = SOURCE_TAG
    private val reportedFiles: MutableSet<VirtualFile> = ConcurrentHashMap.newKeySet()

    /**
     * Clear any problems reported on the previous build run so stale errors don't linger.
     */
    fun resetForNewRun() {
        val solver = WolfTheProblemSolver.getInstance(project)
        val snapshot = reportedFiles.toList()
        reportedFiles.clear()
        snapshot.forEach { solver.clearProblemsFromExternalSource(it, source) }
    }

    /**
     * Mark the file referenced by [problem] as having a Bob-reported issue.
     * Returns the [VirtualFile] that was flagged, or `null` if the path could not be resolved.
     */
    fun report(problem: BobProblem): VirtualFile? {
        val vfile = resolveFile(problem.file) ?: return null
        WolfTheProblemSolver.getInstance(project).reportProblemsFromExternalSource(vfile, source)
        reportedFiles.add(vfile)
        return vfile
    }

    fun report(problems: Iterable<BobProblem>): List<VirtualFile> = problems.mapNotNull(::report)

    private fun resolveFile(path: String): VirtualFile? {
        val lfs = LocalFileSystem.getInstance()
        val basePath = project.basePath
        if (basePath != null) {
            lfs.findFileByPath("$basePath/$path")?.let { return it }
        }
        return lfs.findFileByPath(path)
    }

    companion object {
        private const val SOURCE_TAG = "defold.bob.problems"

        fun Project.bobProblemReporter(): BobProblemReporter = service<BobProblemReporter>()

        /**
         * Lazy variant that returns `null` instead of throwing when the service is not registered
         * (e.g. in unit tests that mock the [Project] without a live service container).
         */
        fun Project.bobProblemReporterOrNull(): BobProblemReporter? = runCatching { bobProblemReporter() }.getOrNull()
    }
}
