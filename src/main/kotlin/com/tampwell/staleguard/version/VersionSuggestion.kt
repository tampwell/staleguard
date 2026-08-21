package com.tampwell.staleguard.version

/**
 * The one place that decides which version Staleguard suggests. Every surface
 * (inspections, planner, tool window) routes through here so a policy change
 * lands everywhere at once.
 *
 * Date-schema rule: a few very old artifacts published date-stamped versions
 * (commons-collections 20040616). Maven's ComparableVersion orders those above
 * any dotted version, so a raw "latest" would recommend a 2004 build over
 * 3.2.1. When the current version is dotted, bare date-like candidates are
 * excluded; a project already on a date-schema version keeps seeing them.
 */
object VersionSuggestion {

    private val DATE_LIKE = Regex("""\d{6,}(\.\d+)?""")

    fun isDateSchema(version: MavenVersion): Boolean = DATE_LIKE.matches(version.value)

    /** Everything suggestible under current policy — the single filter definition. */
    fun candidates(
        current: MavenVersion?,
        available: List<MavenVersion>,
        includePrereleases: Boolean,
        allowed: (MavenVersion) -> Boolean = { true },
    ): List<MavenVersion> {
        val excludeDateSchema = current != null && !isDateSchema(current)
        return available
            .filter { includePrereleases || it.isStable }
            .filterNot { excludeDateSchema && isDateSchema(it) }
            .filter(allowed)
    }

    fun suggest(
        current: MavenVersion?,
        available: List<MavenVersion>,
        includePrereleases: Boolean,
        /** Project pin filter — versions outside a committed ceiling are never suggested. */
        allowed: (MavenVersion) -> Boolean = { true },
    ): MavenVersion? = candidates(current, available, includePrereleases, allowed).maxOrNull()
}
