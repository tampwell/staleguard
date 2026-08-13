package com.tampwell.staleguard.plan

import java.util.concurrent.TimeUnit

/**
 * Data for the age-timeline visualization: one row per unique dependency,
 * bucketed by how long ago its newest release shipped.
 *
 * NOTE: the bar measures time since the NEWEST release of the artifact (the
 * date the cache already holds) — i.e. "how alive is this library" — not the
 * age of the version you happen to use. Per-version release dates would cost
 * one HEAD request per dependency and are deferred.
 */
data class TimelineEntry(
    val label: String,
    val releasedAtMillis: Long?,
    val bucket: AgeBucket,
    /** Newest available upgrade, for the tooltip; null when up to date/unknown. */
    val upgradeHint: String?,
)

enum class AgeBucket { FRESH, AGING, STALE, UNKNOWN }

object TimelineModel {

    private val SIX_MONTHS = TimeUnit.DAYS.toMillis(182)
    private val TWO_YEARS = TimeUnit.DAYS.toMillis(2 * 365)

    fun build(inputs: List<PlannerInput>, plan: UpgradePlan, nowMillis: Long): List<TimelineEntry> {
        val upgradeByCoordinate = plan.candidates.associateBy(
            { it.coordinates.toString() },
            { "${it.currentVersion.value} → ${it.suggestedVersion.value}" },
        )

        return inputs
            .filter { it.declared.groupId != null && it.declared.artifactId != null }
            .distinctBy { it.declared.coordinate }
            .map { input ->
                val releasedAt = input.known?.newestReleaseAtMillis
                val age = releasedAt?.let { nowMillis - it }
                TimelineEntry(
                    label = input.declared.coordinate,
                    releasedAtMillis = releasedAt,
                    bucket = when {
                        age == null -> AgeBucket.UNKNOWN
                        age < SIX_MONTHS -> AgeBucket.FRESH
                        age < TWO_YEARS -> AgeBucket.AGING
                        else -> AgeBucket.STALE
                    },
                    upgradeHint = upgradeByCoordinate[input.declared.coordinate],
                )
            }
            .sortedWith(compareBy(nullsFirst()) { it.releasedAtMillis })
    }
}
