package com.tampwell.staleguard.gradle

/**
 * Parses Gradle string dependency notation: `group:name:version`, optionally
 * `group:name:version:classifier` and/or `...@ext`. Pure logic — the PSI
 * layer hands us the literal's string value and gets back exact indices for
 * surgical version replacement.
 *
 * Interpolated notations (`"org.x:y:${'$'}{version}"`) are the caller's job
 * to reject before parsing (GStrings are not plain literals).
 */
object GradleNotationParser {

    data class Parsed(
        val group: String,
        val name: String,
        val version: String,
        /** Index of the version's first char within the notation string. */
        val versionStartIndex: Int,
    )

    fun parse(notation: String): Parsed? {
        if (notation.isBlank() || "\${" in notation) return null
        val beforeExtension = notation.substringBefore('@')
        val parts = beforeExtension.split(':')
        if (parts.size < 3) return null

        val group = parts[0]
        val name = parts[1]
        val version = parts[2]
        if (group.isEmpty() || name.isEmpty() || version.isEmpty()) return null

        return Parsed(
            group = group,
            name = name,
            version = version,
            versionStartIndex = group.length + 1 + name.length + 1,
        )
    }

    /** The notation with its version swapped, preserving everything else. */
    fun withVersion(notation: String, parsed: Parsed, newVersion: String): String =
        notation.substring(0, parsed.versionStartIndex) +
            newVersion +
            notation.substring(parsed.versionStartIndex + parsed.version.length)
}
