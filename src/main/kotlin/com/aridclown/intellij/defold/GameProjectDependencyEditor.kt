package com.aridclown.intellij.defold

import org.ini4j.Config
import org.ini4j.Ini
import org.ini4j.Profile.Section
import java.io.StringReader
import java.io.StringWriter

/**
 * Reads and edits the `dependencies#N` entries inside `[project]` in a
 * Defold `game.project` INI file. All other keys/sections are left untouched.
 */
object GameProjectDependencyEditor {
    private const val PROJECT_SECTION = "project"
    private const val DEPENDENCY_PREFIX = "dependencies#"

    /**
     * Returns the dependency URLs declared in `[project]`, ordered by their `#N` index.
     */
    fun listDependencies(content: String): List<String> {
        val section = parse(content)[PROJECT_SECTION] ?: return emptyList()
        return section.dependencyEntries().map { (_, url) -> url }
    }

    /**
     * Adds [dependencyUrl] under the next free `dependencies#N` key, or returns
     * the input unchanged when the URL is already present.
     */
    fun appendDependency(content: String, dependencyUrl: String): String {
        require(dependencyUrl.isNotBlank()) { "dependencyUrl must not be blank" }

        val ini = parse(content)
        val section = ini.ensureProjectSection()
        val entries = section.dependencyEntries()
        if (entries.any { (_, url) -> url == dependencyUrl }) return content

        val nextIndex = (entries.maxOfOrNull { (index, _) -> index } ?: -1) + 1
        section["$DEPENDENCY_PREFIX$nextIndex"] = dependencyUrl
        return serialize(ini)
    }

    /**
     * Sets `dependencies#[index]` to [dependencyUrl], replacing whatever value was there.
     */
    fun setDependency(content: String, index: Int, dependencyUrl: String): String {
        require(index >= 0) { "index must be non-negative" }
        require(dependencyUrl.isNotBlank()) { "dependencyUrl must not be blank" }

        val ini = parse(content)
        val section = ini.ensureProjectSection()
        section["$DEPENDENCY_PREFIX$index"] = dependencyUrl
        return serialize(ini)
    }

    private fun Ini.ensureProjectSection(): Section = this[PROJECT_SECTION] ?: run {
        add(PROJECT_SECTION)
        this[PROJECT_SECTION]!!
    }

    private fun Section.dependencyEntries(): List<Pair<Int, String>> = keys
        .asSequence()
        .filter { it.startsWith(DEPENDENCY_PREFIX) }
        .mapNotNull { key ->
            val index = key.removePrefix(DEPENDENCY_PREFIX).toIntOrNull() ?: return@mapNotNull null
            val value = this[key] ?: return@mapNotNull null
            index to value
        }
        .sortedBy { (index, _) -> index }
        .toList()

    private fun parse(content: String): Ini = createIni().apply {
        StringReader(content).use(::load)
    }

    private fun serialize(ini: Ini): String = StringWriter().use { writer ->
        ini.store(writer)
        writer.toString()
    }

    private fun createIni(): Ini = Ini().apply {
        config = Config().apply {
            isEscape = false
            isEmptyOption = true
            isMultiOption = false
            isMultiSection = false
        }
    }
}
