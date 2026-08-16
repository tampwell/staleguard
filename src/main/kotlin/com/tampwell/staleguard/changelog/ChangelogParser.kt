package com.tampwell.staleguard.changelog

/**
 * Extracts one version's section out of a CHANGELOG.md. Real changelogs head
 * sections as "## [1.2.3] - 2024-01-05", "## 1.2.3", "# v1.2.3", or
 * "1.2.3 (2024-01-05)"; the section runs until the next heading that names a
 * different version. Pure text logic, exhaustively testable.
 */
object ChangelogParser {

    private val HEADING = Regex("""^(#{1,4}\s+.*|\S.*)$""")

    /** The markdown body for [version], or null when the file has no section for it. */
    fun sectionFor(changelog: String, version: String): String? {
        val lines = changelog.lines()
        val start = lines.indexOfFirst { isVersionHeading(it, version) }
        if (start < 0) return null

        val body = StringBuilder()
        for (i in start + 1 until lines.size) {
            val line = lines[i]
            if (looksLikeVersionHeading(line) && !isVersionHeading(line, version)) break
            body.appendLine(line)
        }
        return body.toString().trim().ifEmpty { null }
    }

    fun isVersionHeading(line: String, version: String): Boolean {
        if (!looksLikeHeading(line)) return false
        val escaped = Regex.escape(version)
        return Regex("""(^|[\[\s(v])$escaped([]\s)(:—–-]|$)""").containsMatchIn(line)
    }

    /** A heading that names SOME version — the boundary of the previous section. */
    fun looksLikeVersionHeading(line: String): Boolean =
        looksLikeHeading(line) && Regex("""(^|[\[\s(v])\d+(\.\d+)+""").containsMatchIn(line)

    private fun looksLikeHeading(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("#") ||
            // setext/plain styles: a short line that starts with the version-ish token
            (trimmed.isNotEmpty() && !trimmed.startsWith("-") && !trimmed.startsWith("*") && trimmed.length < 80 &&
                Regex("""^\[?v?\d+(\.\d+)+""").containsMatchIn(trimmed))
    }
}

/**
 * Breaking-change signals scanned out of release notes — the input to risk
 * messaging that actual release text backs up, not version-number guessing.
 */
object BreakingSignals {

    private val STRONG = listOf(
        "breaking change", "breaking changes", "backwards incompatible",
        "backward incompatible", "incompatible change", "migration required",
        "migration guide", "no longer supported",
    )
    private val MODERATE = listOf("removed", "renamed", "replaced by", "deprecated")

    data class Scan(val strong: List<String>, val moderate: List<String>) {
        val hasBreaking: Boolean get() = strong.isNotEmpty()
        val isEmpty: Boolean get() = strong.isEmpty() && moderate.isEmpty()
    }

    fun scan(notes: String): Scan {
        val lower = notes.lowercase()
        return Scan(
            strong = STRONG.filter { it in lower },
            moderate = MODERATE.filter { it in lower },
        )
    }
}
