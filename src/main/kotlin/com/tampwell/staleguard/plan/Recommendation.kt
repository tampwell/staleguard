package com.tampwell.staleguard.plan

import com.tampwell.staleguard.version.UpgradeSeverity
import java.util.concurrent.TimeUnit

/**
 * Heuristic guidance for "should I take this upgrade?" — deterministic rules,
 * no AI, no network. Bundle keys: recommendation.safe / .review / .breaking.
 */
enum class Recommendation(val bundleKey: String) {
    SAFE("recommendation.safe"),
    REVIEW("recommendation.review"),
    BREAKING("recommendation.breaking"),
    STALE("recommendation.stale");

    companion object {

        private val SIX_MONTHS = TimeUnit.DAYS.toMillis(182)
        private val ONE_YEAR = TimeUnit.DAYS.toMillis(365)
        private val TWO_YEARS = TimeUnit.DAYS.toMillis(2 * 365)

        /**
         * @param releaseAgeMillis age of the suggested version's release
         *   (null = unknown — we then never claim SAFE, only advise review)
         * @param abandoned true when the newest release is older than the
         *   user's abandonment threshold
         */
        fun of(severity: UpgradeSeverity, releaseAgeMillis: Long?, abandoned: Boolean): Recommendation = when {
            // "Breaking" is a claim about the version distance; age-driven
            // caution is its own thing — a patch to a dormant project is not
            // "breaking changes likely", it's "check the project still fits".
            severity == UpgradeSeverity.MAJOR -> BREAKING
            abandoned -> STALE
            releaseAgeMillis != null && releaseAgeMillis > TWO_YEARS -> STALE
            severity == UpgradeSeverity.PATCH && releaseAgeMillis != null && releaseAgeMillis < SIX_MONTHS -> SAFE
            severity == UpgradeSeverity.MINOR && releaseAgeMillis != null && releaseAgeMillis < ONE_YEAR -> REVIEW
            else -> REVIEW
        }
    }
}
