package com.tampwell.staleguard.toolwindow

import com.intellij.openapi.project.Project
import com.tampwell.staleguard.plan.ModuleStats
import com.tampwell.staleguard.plan.StatsCalculator
import com.tampwell.staleguard.plan.UpgradePlanner
import com.tampwell.staleguard.policy.ProjectPolicyService
import com.tampwell.staleguard.services.VulnerabilityService
import com.tampwell.staleguard.settings.StaleguardSettings
import java.util.concurrent.TimeUnit

/**
 * The project-wide numbers, computed the same way for every surface that
 * shows them. Walks the Maven DOM and the file index, so callers must run
 * it inside a read action off the EDT.
 */
internal object ProjectSummary {

    class WithDrift(val stats: ModuleStats, val lockDrifts: Int)

    fun compute(project: Project, nowMillis: Long = System.currentTimeMillis()): ModuleStats =
        computeWithDrift(project, nowMillis).stats

    fun computeWithDrift(project: Project, nowMillis: Long = System.currentTimeMillis()): WithDrift {
        val settings = StaleguardSettings.getInstance()
        val thresholdMs = TimeUnit.DAYS.toMillis(365L * settings.state.abandonmentYears)
        val inputs = BuildFileRows.collect(project).map { it.input }
        val policy = ProjectPolicyService.getInstance(project)
        val plan = UpgradePlanner.plan(
            inputs, settings.state.suggestPrereleases, thresholdMs, policy::isIgnored, nowMillis,
            versionAllowed = policy::versionAllowed,
            measuredImpact = com.tampwell.staleguard.impact.ImpactMemory.getInstance(project).lookup(),
        )
        val stats = StatsCalculator.summary(
            StatsCalculator.compute(
                inputs, plan, thresholdMs, nowMillis,
                VulnerabilityService.getInstance().advisoryCounter(),
            ),
        )
        val lockEntries = com.tampwell.staleguard.gradle.LockfileScan.collect(project)
        // The status bar recompute is the always-alive surface, so relock
        // detection (and its introduces-warning) rides along here too.
        com.tampwell.staleguard.gradle.RelockNotifier.process(project, lockEntries)
        val drifts = com.tampwell.staleguard.gradle.LockfileScan.driftsFor(lockEntries, inputs)
        return WithDrift(stats, drifts.size)
    }
}
