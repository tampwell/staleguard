package com.tampwell.staleguard.plan

import com.tampwell.staleguard.model.DeclaredDependency
import com.tampwell.staleguard.repository.ArtifactVersions
import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.version.MavenVersion
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsCalculatorTest {

    private val now = 1_800_000_000_000L
    private val twoYears = TimeUnit.DAYS.toMillis(2 * 365)

    private fun input(
        module: String,
        artifact: String,
        raw: String?,
        versions: List<String>?,
        releasedDaysAgo: Long? = 30,
    ) = PlannerInput(
        moduleName = module,
        declared = DeclaredDependency("g", artifact, raw, raw, DeclaredDependency.Origin.DEPENDENCIES),
        known = versions?.let {
            ArtifactVersions(
                Coordinates("g", artifact),
                it.map(::MavenVersion),
                releasedDaysAgo?.let { d -> now - TimeUnit.DAYS.toMillis(d) },
                stale = false,
            )
        },
        moduleId = module,
    )

    @Test
    fun `computes per-module counts and summary`() {
        val inputs = listOf(
            input("app", "a", "1.0.0", listOf("1.0.0", "1.0.1")), // patch update
            input("app", "b", "1.0", listOf("1.0", "2.0")), // major update
            input("app", "c", "3.0", null), // unresolved
            input("lib", "d", "1.0", listOf("1.0"), releasedDaysAgo = 3 * 365), // abandoned, current
        )
        val plan = UpgradePlanner.plan(inputs, false, twoYears, { _, _ -> false }, now)
        val stats = StatsCalculator.compute(inputs, plan, twoYears, now)

        val app = stats.first { it.moduleName == "app" }
        assertEquals(3, app.totalDependencies)
        assertEquals(1, app.unresolved)
        assertEquals(1, app.patchUpdates)
        assertEquals(1, app.majorUpdates)
        assertEquals(0, app.abandoned)

        val lib = stats.first { it.moduleName == "lib" }
        assertEquals(1, lib.totalDependencies)
        assertEquals(0, lib.totalUpdates)
        assertEquals(1, lib.abandoned)

        val summary = StatsCalculator.summary(stats)
        assertEquals(4, summary.totalDependencies)
        assertEquals(2, summary.totalUpdates)
        assertEquals(1, summary.abandoned)
        assertEquals(1, summary.unresolved)
    }

    @Test
    fun `counts vulnerable dependencies per module and in the summary`() {
        val inputs = listOf(
            input("app", "log4j-core", "2.14.1", listOf("2.14.1", "2.25.0")),
            input("app", "clean", "1.0.0", listOf("1.0.0")),
            input("lib", "unresolved", "1.0", null), // no version data, still countable by version
        )
        val plan = UpgradePlanner.plan(inputs, false, twoYears, { _, _ -> false }, now)
        val stats = StatsCalculator.compute(inputs, plan, twoYears, now) { _, artifact, version ->
            if (artifact == "log4j-core" && version == "2.14.1") 7 else 0
        }

        assertEquals(1, stats.first { it.moduleName == "app" }.vulnerable)
        assertEquals(0, stats.first { it.moduleName == "lib" }.vulnerable)
        assertEquals(1, StatsCalculator.summary(stats).vulnerable)
    }

    @Test
    fun `vulnerable defaults to zero when no advisory counter is supplied`() {
        val inputs = listOf(input("app", "a", "1.0.0", listOf("1.0.0", "1.0.1")))
        val plan = UpgradePlanner.plan(inputs, false, twoYears, { _, _ -> false }, now)
        assertEquals(0, StatsCalculator.summary(StatsCalculator.compute(inputs, plan, twoYears, now)).vulnerable)
    }

    @Test
    fun `empty input yields empty stats and zero summary`() {
        val stats = StatsCalculator.compute(emptyList(), UpgradePlan(emptyList(), emptyMap()), twoYears, now)
        assertEquals(0, stats.size)
        assertEquals(0, StatsCalculator.summary(stats).totalDependencies)
    }
}
