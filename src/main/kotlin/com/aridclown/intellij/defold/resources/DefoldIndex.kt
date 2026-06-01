package com.aridclown.intellij.defold.resources

/** Kind of a Defold resource file the indexer cares about. */
enum class DefoldFileKind { Go, Collection }

/**
 * A component declaration inside a `.go` (or inlined inside an embedded instance).
 *
 * - [componentRef] is the value of a `component:` field (path to e.g. a `.script`/`.collectionproxy`).
 * - [typeRef]      is the value of a `type:` field on an embedded component (e.g. `sprite`, `label`).
 *   Exactly one of the two is normally set; both null is tolerated for unknown shapes.
 */
data class ParsedComponent(
    val id: String,
    val componentRef: String? = null,
    val typeRef: String? = null
)

/**
 * An instance declaration inside a `.collection`.
 *
 * - [prototypeRef]  is the value of a `prototype:` field (path to a `.go`).
 * - [collectionRef] is the value of a `collection:` field (path to a sub-`.collection`).
 * - [embeddedComponents] holds the components parsed out of an `embedded_instances` `data:` blob.
 */
data class ParsedInstance(
    val id: String,
    val prototypeRef: String? = null,
    val collectionRef: String? = null,
    val embeddedComponents: List<ParsedComponent> = emptyList()
)

/**
 * Parsed representation of a single `.go` / `.collection` file, keyed by [key] (e.g. `/foo/bar.go`).
 */
data class ParsedFile(
    val key: String,
    val kind: DefoldFileKind,
    val instances: List<ParsedInstance> = emptyList(),
    val components: List<ParsedComponent> = emptyList()
)

/** A completion-ready URL entry resolved from one parsed file (possibly recursively expanded). */
data class DefoldUrlEntry(
    val url: String,
    val typeLabel: String,
    val sourceKey: String
)

/**
 * Snapshot index of every parsed `.go` / `.collection` file in the project plus the URLs resolved
 * from them. Built immutably from a list of [ParsedFile]s. Pure Kotlin (no IntelliJ deps) so the
 * parser/resolver can be unit-tested standalone.
 */
class DefoldIndex(parsedFiles: List<ParsedFile>) {
    private val filesByKey: Map<String, ParsedFile> = parsedFiles.associateBy { it.key }

    /** Components contained as `component:` values inside any file, keyed by component path. */
    private val scriptHosts: Map<String, Set<String>> = buildScriptHostMap(parsedFiles)

    /** Lazily-computed URLs reachable from each file key (full recursive resolution). */
    private val urlsByKey: Map<String, List<DefoldUrlEntry>> = filesByKey.mapValues { (_, file) ->
        resolveUrls(file)
    }

    /** All instance URLs across every parsed `.collection` (top-level instances only). */
    fun allInstanceUrls(): List<DefoldUrlEntry> = filesByKey.values
        .filter { it.kind == DefoldFileKind.Collection }
        .flatMap { urlsByKey.getValue(it.key) }
        .distinctBy { it.url }

    /** URLs reachable from a given host file (the `.go`/`.collection` a `.script` is attached to). */
    fun urlsForHost(hostKey: String): List<DefoldUrlEntry> = urlsByKey[hostKey].orEmpty()

    /** Returns the `.go`/`.collection` files that include [resourcePath] as a `component:` value. */
    fun hostsForResource(resourcePath: String): Set<String> = scriptHosts[resourcePath].orEmpty()

    /**
     * Returns every `.collection` whose recursive expansion reaches [targetKey] — either directly
     * (an instance with `prototype: targetKey`) or transitively (an instance with `collection: ...`
     * that itself contains [targetKey]).
     */
    fun collectionsContaining(targetKey: String): Set<String> {
        val direct = filesByKey.values
            .filter { it.kind == DefoldFileKind.Collection && it.instances.any { i -> i.prototypeRef == targetKey } }
            .map { it.key }
            .toMutableSet()

        val out = direct.toMutableSet()
        val queue = ArrayDeque(direct)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            filesByKey.values.forEach { file ->
                if (file.kind == DefoldFileKind.Collection && file.key !in out &&
                    file.instances.any { it.collectionRef == current }
                ) {
                    out.add(file.key)
                    queue.addLast(file.key)
                }
            }
        }
        return out
    }

    private fun resolveUrls(file: ParsedFile): List<DefoldUrlEntry> = when (file.kind) {
        DefoldFileKind.Go -> file.components.map { component ->
            DefoldUrlEntry("#${component.id}", component.label(), file.key)
        }

        DefoldFileKind.Collection -> file.instances.flatMap { instance ->
            expandInstance(instance, prefix = "", visited = mutableSetOf(file.key))
        }
    }

    private fun expandInstance(
        instance: ParsedInstance,
        prefix: String,
        visited: MutableSet<String>
    ): List<DefoldUrlEntry> {
        val basePath = "$prefix/${instance.id}"
        val out = mutableListOf<DefoldUrlEntry>()
        out += DefoldUrlEntry(basePath, "instance", currentSourceKey(instance))

        instance.embeddedComponents.forEach { component ->
            out += DefoldUrlEntry("$basePath#${component.id}", component.label(), currentSourceKey(instance))
        }

        instance.prototypeRef?.let { protoKey ->
            val proto = filesByKey[protoKey]
            if (proto != null && proto.kind == DefoldFileKind.Go) {
                proto.components.forEach { component ->
                    out += DefoldUrlEntry("$basePath#${component.id}", component.label(), proto.key)
                }
            }
        }

        instance.collectionRef?.let { childKey ->
            if (visited.add(childKey)) {
                val child = filesByKey[childKey]
                if (child != null && child.kind == DefoldFileKind.Collection) {
                    child.instances.forEach { childInstance ->
                        out += expandInstance(childInstance, basePath, visited)
                    }
                }
                visited.remove(childKey)
            }
        }

        return out
    }

    private fun currentSourceKey(instance: ParsedInstance): String = instance.prototypeRef ?: instance.collectionRef ?: ""

    private fun ParsedComponent.label(): String = typeRef ?: componentRef?.substringAfterLast('.', missingDelimiterValue = "") ?: "component"

    private fun buildScriptHostMap(files: List<ParsedFile>): Map<String, Set<String>> {
        val map = mutableMapOf<String, MutableSet<String>>()

        fun record(componentRef: String?, hostKey: String) {
            val ref = componentRef ?: return
            map.getOrPut(ref) { mutableSetOf() }.add(hostKey)
        }

        files.forEach { file ->
            file.components.forEach { record(it.componentRef, file.key) }
            file.instances.forEach { instance ->
                instance.embeddedComponents.forEach { record(it.componentRef, file.key) }
            }
        }
        return map
    }
}
