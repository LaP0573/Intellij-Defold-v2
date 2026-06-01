package com.aridclown.intellij.defold

import com.aridclown.intellij.defold.DefoldProjectService.Companion.defoldVersion
import com.aridclown.intellij.defold.LuarcConfigurationManager.Companion.DEPENDENCY_CACHE_DIRNAME
import com.aridclown.intellij.defold.util.NotificationService
import com.aridclown.intellij.defold.util.NotificationService.notifyInfo
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DependencyAnnotationsManagerTest {
    private val project = mockk<Project>(relaxed = true)
    private val fileSystem = mockk<LocalFileSystem>(relaxed = true)
    private lateinit var manager: DependencyAnnotationsManager

    @TempDir
    private lateinit var tempDir: Path

    private lateinit var projectRoot: Path
    private lateinit var libDir: Path
    private lateinit var cacheDir: Path

    @BeforeEach
    fun setUp() {
        mockkStatic(LocalFileSystem::getInstance)
        mockkObject(NotificationService)
        every { LocalFileSystem.getInstance() } returns fileSystem
        every { project.defoldVersion } returns "1.6.5"

        projectRoot = tempDir.resolve("project").also(Files::createDirectories)
        libDir = projectRoot.resolve(".internal").resolve("lib").also(Files::createDirectories)
        cacheDir = tempDir.resolve("cache").resolve(DEPENDENCY_CACHE_DIRNAME)

        every { project.basePath } returns projectRoot.toString()

        manager = DependencyAnnotationsManager(project)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        clearAllMocks()
    }

    @Test
    fun `extracts lua modules listed by embedded game-project include_dirs and registers cache path`() {
        writeLibZip(
            libDir.resolve("orthographic.zip"),
            rootDirName = "orthographic-master",
            includeDirs = "orthographic",
            extraDirs = listOf("example", "test"),
            luaFiles = mapOf(
                "orthographic/camera.lua" to "-- orthographic.camera module",
                "orthographic/utils/math.lua" to "-- orthographic.utils.math",
                "example/main.lua" to "-- example, NOT registered as library",
                "README.md" to "# readme"
            )
        )

        val registeredCacheDir = manager.sync(libDir, cacheDir)

        assertThat(registeredCacheDir).isEqualTo(cacheDir)

        val extractedCamera = cacheDir.resolve("orthographic-master/orthographic/camera.lua")
        val extractedMath = cacheDir.resolve("orthographic-master/orthographic/utils/math.lua")
        val unrelatedExample = cacheDir.resolve("orthographic-master/example/main.lua")
        val unrelatedReadme = cacheDir.resolve("orthographic-master/README.md")

        assertThat(extractedCamera)
            .exists()
            .hasContent("-- orthographic.camera module")
        assertThat(extractedMath)
            .exists()
            .hasContent("-- orthographic.utils.math")
        assertThat(unrelatedExample).doesNotExist()
        assertThat(unrelatedReadme).doesNotExist()

        val luarc = projectRoot.resolve(".luarc.json")
        assertThat(luarc).exists()
        val luarcJson = JsonParser.parseString(Files.readString(luarc)).asJsonObject
        val library = luarcJson.getAsJsonObject("workspace").getAsJsonArray("library").map { it.asString }
        assertThat(library).contains(cacheDir.toAbsolutePath().normalize().toString())
    }

    @Test
    fun `parses include_dirs separated by spaces or commas`() {
        writeLibZip(
            libDir.resolve("multi.zip"),
            rootDirName = "multi-lib",
            includeDirs = "alpha, beta gamma",
            extraDirs = listOf("delta"),
            luaFiles = mapOf(
                "alpha/a.lua" to "-- a",
                "beta/b.lua" to "-- b",
                "gamma/g.lua" to "-- g",
                "delta/d.lua" to "-- delta, not in include_dirs"
            )
        )

        manager.sync(libDir, cacheDir)

        val libBase = cacheDir.resolve("multi-lib")
        assertThat(libBase.resolve("alpha/a.lua")).exists()
        assertThat(libBase.resolve("beta/b.lua")).exists()
        assertThat(libBase.resolve("gamma/g.lua")).exists()
        assertThat(libBase.resolve("delta/d.lua")).doesNotExist()
    }

    @Test
    fun `extracts modules from multiple lib archives into the same cache root`() {
        writeLibZip(
            libDir.resolve("a.zip"),
            rootDirName = "lib-a",
            includeDirs = "a",
            extraDirs = emptyList(),
            luaFiles = mapOf("a/one.lua" to "-- a.one")
        )
        writeLibZip(
            libDir.resolve("b.zip"),
            rootDirName = "lib-b",
            includeDirs = "b",
            extraDirs = emptyList(),
            luaFiles = mapOf("b/two.lua" to "-- b.two")
        )

        manager.sync(libDir, cacheDir)

        assertThat(cacheDir.resolve("lib-a/a/one.lua")).hasContent("-- a.one")
        assertThat(cacheDir.resolve("lib-b/b/two.lua")).hasContent("-- b.two")
    }

    @Test
    fun `clears stale extractions before re-syncing`() {
        writeLibZip(
            libDir.resolve("v1.zip"),
            rootDirName = "lib-v1",
            includeDirs = "src",
            extraDirs = emptyList(),
            luaFiles = mapOf("src/old.lua" to "-- old")
        )
        manager.sync(libDir, cacheDir)
        assertThat(cacheDir.resolve("lib-v1/src/old.lua")).exists()

        Files.delete(libDir.resolve("v1.zip"))
        writeLibZip(
            libDir.resolve("v2.zip"),
            rootDirName = "lib-v2",
            includeDirs = "src",
            extraDirs = emptyList(),
            luaFiles = mapOf("src/new.lua" to "-- new")
        )
        manager.sync(libDir, cacheDir)

        assertThat(cacheDir.resolve("lib-v1/src/old.lua")).doesNotExist()
        assertThat(cacheDir.resolve("lib-v2/src/new.lua")).hasContent("-- new")
    }

    @Test
    fun `skips archives whose embedded game-project has no library section`() {
        writeLibZip(
            libDir.resolve("nolib.zip"),
            rootDirName = "no-library",
            includeDirs = null,
            extraDirs = emptyList(),
            luaFiles = mapOf("nolib/module.lua" to "-- skipped")
        )

        val result = manager.sync(libDir, cacheDir)

        assertThat(result).isNull()
        assertThat(cacheDir.resolve("no-library/nolib/module.lua")).doesNotExist()
        assertThat(projectRoot.resolve(".luarc.json")).doesNotExist()
    }

    @Test
    fun `returns null when the lib directory does not exist`() {
        val missingDir = tempDir.resolve("does-not-exist").resolve("lib")

        val result = manager.sync(missingDir, cacheDir)

        assertThat(result).isNull()
        assertThat(projectRoot.resolve(".luarc.json")).doesNotExist()
    }

    @Test
    fun `rejects cache directory that does not end with the dependency sentinel`() {
        assertThrows<IllegalArgumentException> {
            manager.sync(libDir, tempDir.resolve("wrong-name"))
        }
    }

    @Test
    fun `appends dependency library path while preserving the defold core API entry`() {
        val coreApiDir = tempDir.resolve("core").resolve("defold_api").also(Files::createDirectories)
        every { any<Project>().notifyInfo(any(), any()) } just Runs
        LuarcConfigurationManager().ensureConfiguration(project, coreApiDir)

        writeLibZip(
            libDir.resolve("orthographic.zip"),
            rootDirName = "orthographic-master",
            includeDirs = "orthographic",
            extraDirs = emptyList(),
            luaFiles = mapOf("orthographic/camera.lua" to "-- camera")
        )

        manager.sync(libDir, cacheDir)

        val luarc = JsonParser.parseString(Files.readString(projectRoot.resolve(".luarc.json"))).asJsonObject
        val library = luarc.getAsJsonObject("workspace").getAsJsonArray("library").map { it.asString }
        assertThat(library)
            .contains(coreApiDir.toAbsolutePath().normalize().toString())
            .contains(cacheDir.toAbsolutePath().normalize().toString())
    }

    private fun writeLibZip(
        target: Path,
        rootDirName: String,
        includeDirs: String?,
        extraDirs: List<String>,
        luaFiles: Map<String, String>
    ) {
        target.parent?.let(Files::createDirectories)
        ZipOutputStream(Files.newOutputStream(target)).use { zos ->
            zos.putNextEntry(ZipEntry("$rootDirName/game.project"))
            zos.write(buildGameProjectIni(includeDirs).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            (luaFiles.keys.mapNotNull { it.substringBeforeLast('/', "").takeIf(String::isNotEmpty) } + extraDirs)
                .toSortedSet()
                .forEach { dir ->
                    zos.putNextEntry(ZipEntry("$rootDirName/$dir/"))
                    zos.closeEntry()
                }

            luaFiles.forEach { (path, content) ->
                zos.putNextEntry(ZipEntry("$rootDirName/$path"))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
    }

    private fun buildGameProjectIni(includeDirs: String?): String = buildString {
        appendLine("[project]")
        appendLine("title = fixture")
        appendLine()
        if (includeDirs != null) {
            appendLine("[library]")
            appendLine("include_dirs = $includeDirs")
        }
    }
}
