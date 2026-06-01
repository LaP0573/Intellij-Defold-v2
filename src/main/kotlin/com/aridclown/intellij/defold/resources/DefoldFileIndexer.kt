package com.aridclown.intellij.defold.resources

/**
 * Pure-Kotlin parser for Defold `.go` / `.collection` textproto files.
 *
 * Brace-nested, stack-based: we track which named section we are currently inside and treat the
 * top-of-stack name as the item type when it is one of [INSTANCE_SECTIONS] / [COMPONENT_SECTIONS].
 * No real textproto parser; this only needs the small subset of fields that contribute to URL
 * resolution (`id`, `component`, `type`, `prototype`, `collection`, `data`).
 *
 * Embedded instances carry their nested components as an escaped string under `data:`; we
 * de-escape (`\"` -> `"`, `\n` -> newline) and re-feed the contents through the same parser.
 */
object DefoldFileIndexer {
    fun parseFile(key: String, kind: DefoldFileKind, content: String): ParsedFile {
        val instances = mutableListOf<ParsedInstance>()
        val components = mutableListOf<ParsedComponent>()
        parseInto(content, instances, components)
        return ParsedFile(key = key, kind = kind, instances = instances, components = components)
    }

    private fun parseInto(
        content: String,
        instances: MutableList<ParsedInstance>,
        components: MutableList<ParsedComponent>
    ) {
        val lines = content.lines()
        val stack = ArrayDeque<String>()

        var inItem = false
        var itemKind: String = ""
        var itemDepth = 0
        var fields = mutableMapOf<String, String>()

        fun flush() {
            if (!inItem) return
            val id = fields["id"]
            if (id != null) {
                when (itemKind) {
                    "instance" -> instances += buildInstance(id, fields)

                    "component" -> components += ParsedComponent(
                        id = id,
                        componentRef = fields["component"],
                        typeRef = fields["type"]
                    )
                }
            }
            inItem = false
            fields = mutableMapOf()
        }

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            if (line.startsWith("}")) {
                if (inItem && stack.size == itemDepth) flush()
                if (stack.isNotEmpty()) stack.removeLast()
                continue
            }

            val openMatch = OPEN_BLOCK_REGEX.matchEntire(line)
            if (openMatch != null) {
                val name = openMatch.groupValues[1]
                stack.addLast(name)
                if (!inItem && name in ITEM_SECTIONS) {
                    inItem = true
                    itemKind = if (name in INSTANCE_SECTIONS) "instance" else "component"
                    itemDepth = stack.size
                }
                continue
            }

            if (inItem) {
                val fieldMatch = FIELD_REGEX.matchEntire(line)
                if (fieldMatch != null) {
                    val key = fieldMatch.groupValues[1]
                    if (key in CAPTURED_FIELDS) {
                        fields[key] = stripQuotes(fieldMatch.groupValues[2].trim())
                    }
                }
            }
        }
        if (inItem) flush()
    }

    private fun buildInstance(id: String, fields: Map<String, String>): ParsedInstance {
        val embeddedComponents = mutableListOf<ParsedComponent>()
        fields["data"]?.let { raw ->
            val expanded = unescapeDataBlob(raw)
            parseInto(expanded, mutableListOf(), embeddedComponents)
        }
        return ParsedInstance(
            id = id,
            prototypeRef = fields["prototype"],
            collectionRef = fields["collection"],
            embeddedComponents = embeddedComponents
        )
    }

    private fun unescapeDataBlob(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val n = value[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    else -> out.append(n)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun stripQuotes(s: String): String = if (s.length >= 2 && s.first() == '"' && s.last() == '"') s.substring(1, s.length - 1) else s

    private val OPEN_BLOCK_REGEX = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\s*\\{\\s*$")
    private val FIELD_REGEX = Regex("^([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*(.*)$")

    private val INSTANCE_SECTIONS = setOf("instances", "embedded_instances")
    private val COMPONENT_SECTIONS = setOf("components", "embedded_components")
    private val ITEM_SECTIONS = INSTANCE_SECTIONS + COMPONENT_SECTIONS
    private val CAPTURED_FIELDS = setOf("id", "prototype", "collection", "component", "type", "data")
}
