package com.tampwell.staleguard.gradle

/**
 * Parser and resolver for the subset of `gradle/libs.versions.toml` that
 * dependency declarations actually use: the `[versions]` table and the
 * `[libraries]` table (inline-table and module-string forms). Pure text
 * logic — no TOML plugin dependency, no PSI — so it's exhaustively testable
 * and degrades gracefully on anything exotic (unparseable entries are
 * skipped, never fatal).
 *
 * Accessor normalization: Gradle maps `-`, `_` and `.` in catalog keys to
 * dots in the generated accessors (`kotlin-stdlib` → `libs.kotlin.stdlib`),
 * so lookups compare dot-normalized forms.
 */
object VersionCatalog {

    data class Library(
        val group: String,
        val name: String,
        /** Key into [Parsed.versions], from `version.ref = "x"`. */
        val versionRef: String?,
        /** Inline literal from `version = "1.2"` or `g:a:v` shorthand. */
        val versionLiteral: String?,
    )

    data class Resolved(
        val catalogKey: String,
        val group: String,
        val name: String,
        val version: String,
        /** The `[versions]` key to edit for an update; null = inline version. */
        val versionKey: String?,
    )

    data class Parsed(
        val versions: Map<String, String>,
        val libraries: Map<String, Library>,
    ) {
        /** `libs.kotlin.stdlib` → accessor `kotlin.stdlib` → library + version. */
        fun resolve(accessor: String): Resolved? {
            val wanted = normalize(accessor)
            val (key, library) = libraries.entries.firstOrNull { normalize(it.key) == wanted } ?: return null
            val version = library.versionLiteral ?: library.versionRef?.let { versions[it] } ?: return null
            return Resolved(key, library.group, library.name, version, library.versionRef)
        }

        /** How many libraries share a `[versions]` key — catalog blast radius. */
        fun referenceCount(versionKey: String): Int = libraries.values.count { it.versionRef == versionKey }

        val isEmpty: Boolean get() = versions.isEmpty() && libraries.isEmpty()
    }

    val EMPTY = Parsed(emptyMap(), emptyMap())

    private val TABLE_HEADER = Regex("""^\s*\[([A-Za-z0-9_.-]+)]\s*(?:#.*)?$""")
    private val SIMPLE_VERSION = Regex("""^\s*([A-Za-z0-9_.-]+)\s*=\s*"([^"]+)"\s*(?:#.*)?$""")
    private val LIBRARY_LINE = Regex("""^\s*([A-Za-z0-9_.-]+)\s*=\s*(.+?)\s*(?:#.*)?$""")
    private val KV = Regex("""([A-Za-z0-9_.-]+)\s*=\s*"([^"]*)"""")

    fun normalize(key: String): String = key.replace('-', '.').replace('_', '.')

    fun parse(text: String): Parsed {
        val versions = mutableMapOf<String, String>()
        val libraries = mutableMapOf<String, Library>()
        var table = ""

        for (line in text.lineSequence()) {
            val header = TABLE_HEADER.matchEntire(line)
            if (header != null) {
                table = header.groupValues[1]
                continue
            }
            when (table) {
                "versions" -> SIMPLE_VERSION.matchEntire(line)?.let {
                    versions[it.groupValues[1]] = it.groupValues[2]
                }

                "libraries" -> LIBRARY_LINE.matchEntire(line)?.let { match ->
                    val key = match.groupValues[1]
                    parseLibraryValue(match.groupValues[2])?.let { libraries[key] = it }
                }
            }
        }
        return Parsed(versions, libraries)
    }

    private fun parseLibraryValue(value: String): Library? {
        // Shorthand: key = "group:name:version"
        if (value.startsWith('"')) {
            val literal = value.trim().removeSurrounding("\"")
            val parts = literal.split(':')
            return when (parts.size) {
                3 -> Library(parts[0], parts[1], versionRef = null, versionLiteral = parts[2])
                else -> null
            }
        }
        if (!value.startsWith('{')) return null

        val pairs = KV.findAll(value).associate { it.groupValues[1] to it.groupValues[2] }
        val module = pairs["module"]
        val group: String?
        val name: String?
        if (module != null) {
            val parts = module.split(':')
            if (parts.size != 2) return null
            group = parts[0]
            name = parts[1]
        } else {
            group = pairs["group"]
            name = pairs["name"]
        }
        if (group.isNullOrEmpty() || name.isNullOrEmpty()) return null

        return Library(
            group = group,
            name = name,
            versionRef = pairs["version.ref"],
            versionLiteral = pairs["version"],
        )
    }

    /**
     * The edit for a catalog version bump: locate `key = "old"` inside the
     * `[versions]` table and return the exact text range of the old version.
     * Pure so the tricky part (not touching same-named keys in other tables)
     * is testable without a Document.
     */
    fun versionValueRange(text: String, versionKey: String): IntRange? {
        var inVersions = false
        var offset = 0
        for (line in text.lineSequence()) {
            TABLE_HEADER.matchEntire(line)?.let { inVersions = it.groupValues[1] == "versions" }
            if (inVersions) {
                SIMPLE_VERSION.matchEntire(line)?.let { match ->
                    if (match.groupValues[1] == versionKey) {
                        val valueStart = offset + line.indexOf('"') + 1
                        return valueStart until (valueStart + match.groupValues[2].length)
                    }
                }
            }
            offset += line.length + 1 // +1 for the newline
        }
        return null
    }
}
