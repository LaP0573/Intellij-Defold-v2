package com.aridclown.intellij.defold.resources

import com.aridclown.intellij.defold.DefoldCoroutineService.Companion.launch
import com.aridclown.intellij.defold.DefoldProjectService.Companion.rootProjectFolder
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level.PROJECT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicReference

/**
 * Project-wide index of Defold `.go` / `.collection` resources, exposing URL completions for the
 * Lua autocomplete contributor. Owns the read-action driven scan/parse cycle and the debounced
 * VFS-triggered rebuild.
 */
@Service(PROJECT)
class DefoldUrlIndexService(private val project: Project) {
    private val logger = Logger.getInstance(DefoldUrlIndexService::class.java)
    private val cache = AtomicReference<DefoldIndex?>(null)

    @Volatile
    private var pendingRebuild: Job? = null

    /**
     * Returns the current index snapshot; builds it synchronously the first time it is needed so
     * that completion always has something to show even before VFS events have fired.
     */
    fun getIndex(): DefoldIndex = cache.get() ?: buildSync()

    /** Drops the cached snapshot; the next [getIndex] call rebuilds. */
    fun invalidate() {
        cache.set(null)
    }

    /** Schedules a debounced rebuild on the project coroutine scope. Safe to call from VFS events. */
    fun scheduleRebuild() {
        pendingRebuild?.cancel()
        pendingRebuild = project.launch {
            delay(DEBOUNCE_MS)
            buildSync()
        }
    }

    private fun buildSync(): DefoldIndex {
        val snapshot = ReadAction.compute<DefoldIndex, RuntimeException> {
            val root = project.rootProjectFolder ?: return@compute DefoldIndex(emptyList())
            val parsed = collectFiles(root).mapNotNull { vf ->
                val rel = VfsUtilCore.getRelativePath(vf, root, '/') ?: return@mapNotNull null
                val kind = fileKindFor(vf) ?: return@mapNotNull null
                runCatching {
                    DefoldFileIndexer.parseFile(
                        key = "/$rel",
                        kind = kind,
                        content = VfsUtilCore.loadText(vf)
                    )
                }.onFailure { logger.warn("Failed to parse Defold resource $rel", it) }
                    .getOrNull()
            }
            DefoldIndex(parsed)
        }
        cache.set(snapshot)
        return snapshot
    }

    private fun collectFiles(root: VirtualFile): List<VirtualFile> {
        val out = mutableListOf<VirtualFile>()
        VfsUtilCore.visitChildrenRecursively(
            root,
            object : VirtualFileVisitor<Void>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (file.isDirectory) {
                        if (file != root && file.name in DEFOLD_INDEX_EXCLUDES) return false
                        return true
                    }
                    if (fileKindFor(file) != null) out.add(file)
                    return true
                }
            }
        )
        return out
    }

    companion object {
        private const val DEBOUNCE_MS = 400L

        private val DEFOLD_INDEX_EXCLUDES = setOf(".git", ".idea", "build", ".internal", "debugger")

        fun fileKindFor(file: VirtualFile): DefoldFileKind? = when {
            file.isDirectory -> null
            file.name.endsWith(".go") -> DefoldFileKind.Go
            file.name.endsWith(".collection") -> DefoldFileKind.Collection
            else -> null
        }

        fun Project.defoldUrlIndexService(): DefoldUrlIndexService = service<DefoldUrlIndexService>()
    }
}
