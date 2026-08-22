package com.tampwell.staleguard.gradle

import com.intellij.openapi.vfs.VirtualFile

/**
 * gradle.properties, as far as version declarations care: `key=value` lines
 * with exact value ranges so a quick fix can edit in place. Pure line logic
 * plus the same walk-up file location the version catalog uses.
 */
object GradleProperties {

    private val ENTRY = Regex("""^\s*([A-Za-z0-9_.\-]+)\s*[=:]\s*(.*?)\s*$""")

    fun parse(text: String): Map<String, String> {
        val values = mutableMapOf<String, String>()
        for (line in text.lineSequence()) {
            if (line.trimStart().startsWith("#") || line.trimStart().startsWith("!")) continue
            val match = ENTRY.matchEntire(line) ?: continue
            values[match.groupValues[1]] = match.groupValues[2] // last wins, like java.util.Properties
        }
        return values
    }

    /**
     * Exact range of the value for [key], for in-place document edits.
     * A whole-text multiline match keeps offsets right on CRLF files
     * (trailing \r lands in the \s* tail, never in the value group).
     * The LAST assignment is the effective one — same rule as parse().
     */
    fun valueRange(text: String, key: String): IntRange? =
        Regex("""(?m)^\s*${Regex.escape(key)}\s*[=:][ \t]*(.*?)[ \t\r]*$""")
            .findAll(text)
            .lastOrNull()
            ?.groups?.get(1)?.range

    /** Walks up from the build file, same bounded search as the catalog. */
    fun findFile(buildFile: VirtualFile?): VirtualFile? {
        var dir = buildFile?.parent
        var depth = 0
        while (dir != null && depth < 6) {
            dir.findChild("gradle.properties")?.let { return it }
            dir = dir.parent
            depth++
        }
        return null
    }
}
