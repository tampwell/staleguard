package com.tampwell.staleguard.version

private val PRERELEASE_MARKERS = listOf("alpha", "beta", "milestone", "rc", "snapshot")

/**
 * True when this version is a release users should be offered: no SNAPSHOT and
 * no prerelease qualifier. Judged token-by-token against Maven's *canonical*
 * form so aliases are caught (3.0M3 → milestone) and mere substrings are not
 * ("2.0-arch" contains "rc" but is stable).
 */
val MavenVersion.isStable: Boolean
    get() = canonical.split('.', '-').none { token ->
        PRERELEASE_MARKERS.any { marker ->
            token == marker ||
                (token.length > marker.length &&
                    token.startsWith(marker) &&
                    token.substring(marker.length).all(Char::isDigit))
        }
    }
