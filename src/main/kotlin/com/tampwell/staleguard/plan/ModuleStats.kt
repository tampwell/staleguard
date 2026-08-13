package com.tampwell.staleguard.plan

import com.tampwell.staleguard.version.UpgradeSeverity

/** Freshness numbers for one Maven module — the stats window's row model. */
data class ModuleStats(
    val moduleName: String,
    val totalDependencies: Int,
    val unresolved: Int,
    val patchUpdates: Int,
    val minorUpdates: Int,
    val majorUpdates: Int,
    val qualifierUpdates: Int,
    val abandoned: Int,
) {
    val totalUpdates: Int get() = patchUpdates + minorUpdates + majorUpdates + qualifierUpdates

    operator fun plus(other: ModuleStats): ModuleStats = ModuleStats(
        moduleName = "",
        totalDependencies = totalDependencies + other.totalDependencies,
        unresolved = unresolved + other.unresolved,
        patchUpdates = patchUpdates + other.patchUpdates,
        minorUpdates = minorUpdates + other.minorUpdates,
        majorUpdates = majorUpdates + other.majorUpdates,
        qualifierUpdates = qualifierUpdates + other.qualifierUpdates,
        abandoned = abandoned + other.abandoned,
    )
}

/**
 * Aggregates planner inputs + candidates into per-module numbers. Pure logic;
 * abandonment counts every dependency whose newest release is older than the
 * threshold — independent of whether an upgrade exists.
 */
object StatsCalculator {

    fun compute(
        inputs: List<PlannerInput>,
        plan: UpgradePlan,
        abandonmentThresholdMillis: Long,
        nowMillis: Long,
    ): List<ModuleStats> {
        val byModule = inputs.groupBy { it.moduleId }
        val candidatesByModule = plan.candidates.groupBy { it.moduleId }

        return byModule.map { (moduleId, moduleInputs) ->
            val candidates = candidatesByModule[moduleId].orEmpty()
            fun count(severity: UpgradeSeverity) = candidates.count { it.severity == severity }
            ModuleStats(
                moduleName = moduleInputs.first().moduleName,
                totalDependencies = moduleInputs.size,
                unresolved = moduleInputs.count { it.known == null },
                patchUpdates = count(UpgradeSeverity.PATCH),
                minorUpdates = count(UpgradeSeverity.MINOR),
                majorUpdates = count(UpgradeSeverity.MAJOR),
                qualifierUpdates = count(UpgradeSeverity.QUALIFIER),
                abandoned = moduleInputs.count { input ->
                    val releasedAt = input.known?.newestReleaseAtMillis
                    releasedAt != null && nowMillis - releasedAt > abandonmentThresholdMillis
                },
            )
        }.sortedBy { it.moduleName }
    }

    fun summary(stats: List<ModuleStats>): ModuleStats =
        stats.fold(ModuleStats("", 0, 0, 0, 0, 0, 0, 0), ModuleStats::plus)
}
