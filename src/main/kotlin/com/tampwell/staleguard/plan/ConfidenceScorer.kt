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

    private val ONE_WEEK = TimeUnit.DAYS.toMillis(7)
    private val TWO_YEARS = TimeUnit.DAYS.toMillis(2 * 365)

    fun score(
        severity: UpgradeSeverity,
        releaseAgeMillis: Long?,
        abandoned: Boolean,
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

        return UpdateConfidence(score.coerceIn(0, 100), factors)
    }
}
