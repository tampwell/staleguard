package com.tampwell.staleguard.inspection

import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.plan.Recommendation
import com.tampwell.staleguard.util.RelativeTime
import com.tampwell.staleguard.version.UpgradeSeverity

/**
 * The outdated-dependency message, assembled in exactly one place for all four
 * inspection surfaces — the wording and argument order live here so the
 * surfaces cannot drift (the vulnerability and license checks already follow
 * this pattern via VulnerabilityProblems and LicenseProblems).
 */
object FreshnessProblems {

    fun message(
        severity: UpgradeSeverity,
        currentVersion: String,
        suggestedVersion: String,
        recommendation: Recommendation,
        releaseAgeMillis: Long?,
    ): String = if (releaseAgeMillis != null) {
        StaleguardBundle.message(
            "inspection.outdated.message",
            severityLabel(severity),
            currentVersion,
            suggestedVersion,
            StaleguardBundle.message(recommendation.bundleKey),
            RelativeTime.ago(releaseAgeMillis),
        )
    } else {
        StaleguardBundle.message(
            "inspection.outdated.message.noage",
            severityLabel(severity),
            currentVersion,
            suggestedVersion,
            StaleguardBundle.message(recommendation.bundleKey),
        )
    }

    fun severityLabel(severity: UpgradeSeverity): String =
        StaleguardBundle.message("severity.${severity.name.lowercase()}")
}
