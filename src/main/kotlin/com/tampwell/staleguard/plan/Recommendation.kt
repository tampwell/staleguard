package com.tampwell.staleguard.plan

import com.tampwell.staleguard.version.UpgradeSeverity
import java.util.concurrent.TimeUnit

/**
 * Heuristic guidance for "should I take this upgrade?" — deterministic rules,
 * no AI, no network. Bundle keys: recommendation.urgent / .safe / .review /
 * .breaking / .stale.
 */
enum class Recommendation(val bundleKey: String) {
    URGENT("recommendation.urgent"),
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
         * @param vulnerable true when the CURRENT version carries a known
         *   vulnerability — overrides every hesitation below, because "review
         *   the changelog first" is the wrong advice while shipping a CVE
         */
        fun of(
            severity: UpgradeSeverity,
            releaseAgeMillis: Long?,
            abandoned: Boolean,
            vulnerable: Boolean = false,
            /**
             * False for build plugins: core Maven plugins go years between
             * releases while being perfectly healthy, so age must not read
             * as "project looks inactive" there.
             */
            ageDrivenStale: Boolean = true,
        ): Recommendation = when {
            vulnerable -> URGENT
            // "Breaking" is a claim about the version distance; age-driven
            // caution is its own thing — a patch to a dormant project is not
            // "breaking changes likely", it's "check the project still fits".
            severity == UpgradeSeverity.MAJOR -> BREAKING
            abandoned -> STALE
            ageDrivenStale && releaseAgeMillis != null && releaseAgeMillis > TWO_YEARS -> STALE
            severity == UpgradeSeverity.PATCH && releaseAgeMillis != null && releaseAgeMillis < SIX_MONTHS -> SAFE
            severity == UpgradeSeverity.MINOR && releaseAgeMillis != null && releaseAgeMillis < ONE_YEAR -> REVIEW
            else -> REVIEW
        }
    }
}
