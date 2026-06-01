package com.aridclown.intellij.defold

import com.aridclown.intellij.defold.DefoldConstants.GAME_PROJECT_FILE
import com.aridclown.intellij.defold.DefoldConstants.PLUGIN_DIRECTORY_NAME
import com.aridclown.intellij.defold.LuarcConfigurationManager.Companion.DEPENDENCY_CACHE_DIRNAME
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.ini4j.Ini
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import java.util.zip.ZipFile
import kotlin.io.path.name

/**
 * Discovers the dependency zip archives Bob downloads into `<project>/.internal/lib`, extracts
 * each archive's Lua modules under the directories its embedded `game.project` lists in
 * `[library].include_dirs`, and registers the resulting per-project cache root with
 * [LuarcConfigurationManager] so LuaLS / EmmyLua2 pick up library-level autocomplete.
 *
 * Mirrors the per-dependency annotation flow both VSCode extensions (Defold Kit + Defold Buddy)
 * implement; until this lands IntelliJ only ships core-API annotations.
 */
class DependencyAnnotationsManager(
    private val project: Project,
    private val luarcManager: LuarcConfigurationManager = LuarcConfigurationManager()
) {
    private val logger = Logger.getInstance(DependencyAnnotationsManager::class.java)

    /**
     * Production entry point. Resolves the project's `.internal/lib` directory and a per-project
     * cache directory under the IDE system path, then performs a clean sync. Returns the cache
     * directory the LuaLS library was registered against, or `null` when the project has no
     * dependencies yet (no `.internal/lib` directory).
     */
    fun sync(): Path? {
        val libDir = libDir(project) ?: return null
        if (!Files.isDirectory(libDir)) return null

        val cacheDir = cacheDir(project)
        return sync(libDir, cacheDir)
    }

    /**
     * Testable entry point. [libDir] is expected to contain Bob-downloaded `*.zip` archives;
     * [cacheDir] must end with [LuarcConfigurationManager.Companion.DEPENDENCY_CACHE_DIRNAME] so
     * stale registrations can be replaced. Returns [cacheDir] on success, or `null` when no zip
     * archives are present in [libDir].
     */
    fun sync(libDir: Path, cacheDir: Path): Path? {
        require(cacheDir.name == DEPENDENCY_CACHE_DIRNAME) {
            "dependency cache dir must end with '$DEPENDENCY_CACHE_DIRNAME', got: $cacheDir"
        }
        if (!Files.isDirectory(libDir)) return null

        val zips = Files.list(libDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".zip", ignoreCase = true) }
                .sorted()
                .toList()
        }
        if (zips.isEmpty()) return null

        cacheDir.deleteRecursivelyIfExists()
        Files.createDirectories(cacheDir)

        var extractedAny = false
        zips.forEach { zip ->
            runCatching { extractLuaModules(zip, cacheDir) }
                .onSuccess { extracted -> if (extracted) extractedAny = true }
                .onFailure { error -> logger.warn("Failed to extract Defold dependency annotations from $zip: ${error.message}", error) }
        }

        if (!extractedAny) return null

        luarcManager.ensureDependencyLibrary(project, cacheDir)
        return cacheDir
    }

    private fun extractLuaModules(zip: Path, cacheDir: Path): Boolean {
        ZipFile(zip.toFile()).use { zipFile ->
            val gameProjectEntry = zipFile.entries().asSequence()
                .filter { !it.isDirectory && it.name.substringAfterLast('/') == GAME_PROJECT_FILE }
                .minByOrNull { it.name.count { ch -> ch == '/' } }
                ?: return false.also {
                    logger.info("Skipping ${zip.fileName}: no embedded $GAME_PROJECT_FILE entry")
                }

            val pathPrefix = gameProjectEntry.name.substringBeforeLast('/', missingDelimiterValue = "").let {
                if (it.isEmpty()) "" else "$it/"
            }

            val includeDirs = zipFile.getInputStream(gameProjectEntry).use { stream ->
                runCatching { Ini(stream) }
                    .getOrNull()
                    ?.get(LIBRARY_SECTION)
                    ?.get(INCLUDE_DIRS_KEY)
                    ?.parseIncludeDirs()
                    .orEmpty()
            }
            if (includeDirs.isEmpty()) {
                logger.info("Skipping ${zip.fileName}: $GAME_PROJECT_FILE has no [$LIBRARY_SECTION].$INCLUDE_DIRS_KEY entries")
                return false
            }

            val libName = pathPrefix.removeSuffix("/").substringAfterLast('/')
                .ifEmpty { zip.fileName.toString().removeSuffix(".zip") }
            val libCacheDir = cacheDir.resolve(libName)

            var wrote = false
            zipFile.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".lua", ignoreCase = true) }
                .filter { it.name.startsWith(pathPrefix) }
                .forEach { entry ->
                    val relativeFromPrefix = entry.name.removePrefix(pathPrefix)
                    val matched = includeDirs.any { dir ->
                        relativeFromPrefix == dir ||
                            relativeFromPrefix.startsWith("$dir/")
                    }
                    if (!matched) return@forEach

                    val outPath = libCacheDir.resolve(relativeFromPrefix)
                    outPath.parent?.let(Files::createDirectories)
                    zipFile.getInputStream(entry).use { input ->
                        Files.newOutputStream(outPath).use { output -> input.copyTo(output) }
                    }
                    wrote = true
                }

            return wrote
        }
    }

    private fun String.parseIncludeDirs(): List<String> = split(INCLUDE_DIRS_SEPARATORS)
        .map { it.trim().trim('/') }
        .filter { it.isNotEmpty() }

    private fun libDir(project: Project): Path? = project.basePath
        ?.let { Path.of(it, ".internal", "lib") }

    private fun cacheDir(project: Project): Path {
        val projectKey = projectKey(project)
        return Path.of(PathManager.getSystemPath())
            .resolve(PLUGIN_DIRECTORY_NAME)
            .resolve("deps")
            .resolve(projectKey)
            .resolve(DEPENDENCY_CACHE_DIRNAME)
    }

    private fun projectKey(project: Project): String {
        val basePath = project.basePath ?: project.name
        val digest = MessageDigest.getInstance("SHA-1").digest(basePath.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun Path.deleteRecursivelyIfExists() {
        if (Files.notExists(this)) return
        Files.walk(this).use { stream ->
            stream
                .sorted(Comparator.reverseOrder())
                .forEach { path -> Files.deleteIfExists(path) }
        }
    }

    companion object {
        private const val LIBRARY_SECTION = "library"
        private const val INCLUDE_DIRS_KEY = "include_dirs"
        private val INCLUDE_DIRS_SEPARATORS = Regex("[,\\s]+")

        fun getInstance(project: Project): DependencyAnnotationsManager = DependencyAnnotationsManager(project)
    }
}
