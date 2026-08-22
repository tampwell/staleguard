package com.tampwell.staleguard.version

/**
 * Keeps Staleguard from recommending an upgrade INTO a known vulnerability —
 * log4j 2.15.0 is the canonical case: it fixed Log4Shell and carried its own
 * CVE. If the top suggestion is known-vulnerable and a lower version that is
 * still newer than current is not, steer to it.
 *
 * "Known" is the warm OSV cache only. Unknown status counts as acceptable:
 * the cache rarely knows every version, and refusing to suggest until it
 * does would silence updates entirely. Probing the suggested version through
 * the shared advisory path warms it via the existing request batch — no new
 * traffic class.
 */
object SuggestionSafety {

    data class Steered(
        val version: MavenVersion,
        /** True when every acceptable candidate is known-vulnerable — the message says so. */
        val knownVulnerable: Boolean,
    )

    fun steerClear(
        current: MavenVersion?,
        suggested: MavenVersion,
        available: List<MavenVersion>,
        includePrereleases: Boolean,
        allowed: (MavenVersion) -> Boolean,
        knownVulnerable: (MavenVersion) -> Boolean,
    ): Steered {
        if (!knownVulnerable(suggested)) return Steered(suggested, false)
        val alternative = VersionSuggestion.candidates(current, available, includePrereleases, allowed)
            .asSequence()
            .filter { current == null || it > current }
            .filter { it < suggested }
            .sortedDescending()
            .firstOrNull { !knownVulnerable(it) }
        return if (alternative != null) Steered(alternative, false) else Steered(suggested, true)
    }
}
