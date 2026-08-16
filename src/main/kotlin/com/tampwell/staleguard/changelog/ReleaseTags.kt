package com.tampwell.staleguard.changelog

import com.tampwell.staleguard.version.MavenVersion

/**
 * Pure logic for mapping Maven versions onto git release tags — the messy
 * heart of changelog lookup. Projects tag as v1.2.3, 1.2.3, artifact-1.2.3,
 * release-1.2.3, or RELEASE_1_2_3; guessing wrong means "no changelog found"
 * for a release that exists, so candidates are generated in observed-frequency
 * order and matched case-insensitively.
 */
object ReleaseTags {

    /** Tag names that plausibly mark [version], most common first. */
    fun candidates(version: String, artifactId: String?): List<String> {
        val base = mutableListOf("v$version", version)
        artifactId?.let {
            base += "$it-$version"
            base += "$it-v$version"
        }
        base += "release-$version"
        base += "r$version"
        base += "RELEASE_" + version.replace('.', '_')
        return base
    }

    /** True when [tag] plausibly refers to [version] of [artifactId]. */
    fun matches(tag: String, version: String, artifactId: String?): Boolean =
        candidates(version, artifactId).any { it.equals(tag, ignoreCase = true) }

    /**
     * From all known versions, the ones a user skips moving current →
     * suggested: everything above current up to and including suggested, in
     * ascending order. This is the release list whose notes answer "what do I
     * get, and what might break".
     */
    fun skippedRange(current: String, suggested: String, all: List<String>): List<String> {
        val currentVersion = MavenVersion(current)
        val suggestedVersion = MavenVersion(suggested)
        return all.map(::MavenVersion)
            .filter { it > currentVersion && it <= suggestedVersion }
            .sorted()
            .map { it.value }
    }
}
