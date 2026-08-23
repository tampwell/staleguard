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

    fun compute(project: Project, nowMillis: Long = System.currentTimeMillis()): ModuleStats {
        val settings = StaleguardSettings.getInstance()
        val thresholdMs = TimeUnit.DAYS.toMillis(365L * settings.state.abandonmentYears)
        val inputs = BuildFileRows.collect(project).map { it.input }
        val policy = ProjectPolicyService.getInstance(project)
        val plan = UpgradePlanner.plan(
            inputs, settings.state.suggestPrereleases, thresholdMs, policy::isIgnored, nowMillis,
            versionAllowed = policy::versionAllowed,
        )
        return StatsCalculator.summary(
            StatsCalculator.compute(
                inputs, plan, thresholdMs, nowMillis,
                VulnerabilityService.getInstance().advisoryCounter(),
            ),
        )
    }
}
