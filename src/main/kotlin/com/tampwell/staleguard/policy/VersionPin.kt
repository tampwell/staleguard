package com.tampwell.staleguard.policy

import com.tampwell.staleguard.version.MavenVersion
import com.tampwell.staleguard.version.VersionConstraint

/**
 * "This dependency stays inside this version range" — the committed answer to
 * teams that live on an older major on purpose. Pins narrow SUGGESTIONS only:
 * abandonment and vulnerability warnings are facts about the declared version
 * and ignore pins entirely.
 */
data class VersionPin(
    /** `group:artifact` with `*` wildcards, same grammar as ignore patterns. */
    val coordinatePattern: String,
    val constraint: VersionConstraint,
) {
    fun appliesTo(groupId: String, artifactId: String): Boolean =
        IgnoreRules.matches(coordinatePattern, groupId, artifactId)

    fun allows(version: MavenVersion): Boolean = constraint.allows(version)
}
