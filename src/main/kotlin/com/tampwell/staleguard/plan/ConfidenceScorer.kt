package com.tampwell.staleguard.plan

import com.tampwell.staleguard.version.UpgradeSeverity
import java.util.concurrent.TimeUnit

/** A 0–100 "how safe is this upgrade" score with its contributing factors. */
data class UpdateConfidence(val score: Int, val factors: List<Factor>) {
    data class Factor(val bundleKey: String, val impact: Int)
}

/**
 * Deterministic multi-factor confidence scoring — no AI, no network, no
 * external metrics. Factors: severity, release age, abandonment. Adoption
 * metrics were considered and REJECTED as infeasible: Maven Central publishes
 * no download statistics (documented in PROGRESS.md).
 */
object ConfidenceScorer {

    /** Highest score a measured-breaking upgrade can reach, however good it looks otherwise. */
    const val BREAKS_CEILING = 20

    private val ONE_WEEK = TimeUnit.DAYS.toMillis(7)
    private val TWO_YEARS = TimeUnit.DAYS.toMillis(2 * 365)

    fun score(
        severity: UpgradeSeverity,
        releaseAgeMillis: Long?,
        abandoned: Boolean,
        vulnerable: Boolean = false,
        /** What an actual binary comparison found, when the user has run one. */
        measured: MeasuredImpact = MeasuredImpact.Unknown,
    ): UpdateConfidence {
        var score = 50
        val factors = mutableListOf<UpdateConfidence.Factor>()

        fun add(key: String, impact: Int) {
            score += impact
            factors.add(UpdateConfidence.Factor(key, impact))
        }

        when (severity) {
            UpgradeSeverity.PATCH -> add("confidence.factor.patch", +25)
            UpgradeSeverity.MINOR -> add("confidence.factor.minor", +10)
            UpgradeSeverity.QUALIFIER -> add("confidence.factor.qualifier", +5)
            UpgradeSeverity.MAJOR -> add("confidence.factor.major", -15)
        }

        when {
            releaseAgeMillis == null -> add("confidence.factor.age.unknown", -10)
            releaseAgeMillis < ONE_WEEK -> add("confidence.factor.age.new", -15) // let others find the bugs
            releaseAgeMillis <= TWO_YEARS -> add("confidence.factor.age.mature", +10)
            else -> add("confidence.factor.age.old", -5)
        }

        if (abandoned) add("confidence.factor.abandoned", -20)

        // The calculus inverts when the current version ships a CVE: staying
        // is the risky option, so the update deserves the benefit of the doubt.
        if (vulnerable) add("confidence.factor.vulnerable", +20)

        when (measured) {
            MeasuredImpact.Clean -> add("confidence.factor.impact.clean", +25)
            is MeasuredImpact.Breaks -> add("confidence.factor.impact.breaks", -50)
            MeasuredImpact.Unknown -> Unit
        }

        // A known break is not one factor among several. We have compiled
        // evidence that this project calls something the new version deletes,
        // and no combination of age and severity should be able to talk that
        // back up into a confident-looking number.
        val ceiling = if (measured is MeasuredImpact.Breaks) BREAKS_CEILING else 100
        return UpdateConfidence(score.coerceIn(0, ceiling), factors)
    }
}
