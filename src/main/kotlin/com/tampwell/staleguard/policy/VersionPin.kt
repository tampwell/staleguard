package com.tampwell.staleguard.policy

import com.tampwell.staleguard.version.MavenVersion
import com.tampwell.staleguard.version.VersionConstraint

/**
 * "This dependency stays inside this version range" — the committed answer to
 * teams that live on an older major on purpose. Pins narrow SUGGESTIONS only:
 * abandonment and vulnerability warnings are facts about the declared version
 * and ignore pins entirely.
 *
 * Sources: Staleguard's own `[pins]` table, plus the parity rules read from
 * renovate.json (allowedVersions, no-majors rules) and dependabot.yml
 * (ignore versions ranges, semver-major update-type blocks).
 */
data class VersionPin(
    /** `group:artifact` with `*` wildcards, same grammar as ignore patterns. */
    val coordinatePattern: String,
    val constraint: VersionConstraint? = null,
    /**
     * "No major upgrades" — evaluated against the declared version, because
     * that is what both bots mean by it. Unknown current fails open.
     */
    val blockMajors: Boolean = false,
) {
    fun appliesTo(groupId: String, artifactId: String): Boolean =
        IgnoreRules.matches(coordinatePattern, groupId, artifactId)

    fun allows(current: MavenVersion?, candidate: MavenVersion): Boolean {
        if (constraint?.allows(candidate) == false) return false
        if (blockMajors && current != null && majorOf(candidate) != majorOf(current)) return false
        return true
    }

    /** Kept for single-arg call sites that have no current-version context. */
    fun allows(candidate: MavenVersion): Boolean = allows(null, candidate)

    private fun majorOf(version: MavenVersion): String =
        version.value.substringBefore('.').substringBefore('-')
}
