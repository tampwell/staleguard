package com.tampwell.staleguard.plan

import com.tampwell.staleguard.model.DeclaredDependency
import com.tampwell.staleguard.repository.ArtifactVersions
import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.version.MavenVersion
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineModelTest {

    private val now = 1_800_000_000_000L
    private val twoYears = TimeUnit.DAYS.toMillis(2 * 365)

    private fun input(artifact: String, versions: List<String>?, releasedDaysAgo: Long?, raw: String = "1.0") =
        PlannerInput(
            moduleName = "app",
            declared = DeclaredDependency("g", artifact, raw, raw, DeclaredDependency.Origin.DEPENDENCIES),
            known = versions?.let {
                ArtifactVersions(
                    Coordinates("g", artifact),
                    it.map(::MavenVersion),
                    releasedDaysAgo?.let { d -> now - TimeUnit.DAYS.toMillis(d) },
                    stale = false,
                )
            },
        )

    private fun buildEntries(vararg inputs: PlannerInput): List<TimelineEntry> {
        val plan = UpgradePlanner.plan(inputs.toList(), false, twoYears, { _, _ -> false }, now)
        return TimelineModel.build(inputs.toList(), plan, now)
    }

    @Test
    fun `buckets by age of newest release`() {
        val entries = buildEntries(
            input("fresh", listOf("1.0"), releasedDaysAgo = 30),
            input("aging", listOf("1.0"), releasedDaysAgo = 365),
            input("stale", listOf("1.0"), releasedDaysAgo = 3 * 365),
            input("unknown", listOf("1.0"), releasedDaysAgo = null),
            input("unresolved", null, releasedDaysAgo = null),
        )
        val byLabel = entries.associateBy { it.label }
        assertEquals(AgeBucket.FRESH, byLabel["g:fresh"]?.bucket)
        assertEquals(AgeBucket.AGING, byLabel["g:aging"]?.bucket)
        assertEquals(AgeBucket.STALE, byLabel["g:stale"]?.bucket)
        assertEquals(AgeBucket.UNKNOWN, byLabel["g:unknown"]?.bucket)
        assertEquals(AgeBucket.UNKNOWN, byLabel["g:unresolved"]?.bucket)
    }

    @Test
    fun `upgrade hint present only when an upgrade exists`() {
        val entries = buildEntries(
            input("old", listOf("1.0", "1.1"), releasedDaysAgo = 30),
            input("current", listOf("1.0"), releasedDaysAgo = 30),
        )
        val byLabel = entries.associateBy { it.label }
        assertEquals("1.0 → 1.1", byLabel["g:old"]?.upgradeHint)
        assertNull(byLabel["g:current"]?.upgradeHint)
    }

    @Test
    fun `duplicate coordinates across modules are deduped`() {
        val a = input("dup", listOf("1.0"), releasedDaysAgo = 10)
        val b = input("dup", listOf("1.0"), releasedDaysAgo = 10).copy(moduleName = "lib", moduleId = "lib")
        assertEquals(1, buildEntries(a, b).size)
    }

    @Test
    fun `sorted oldest first with unknown leading`() {
        val entries = buildEntries(
            input("new", listOf("1.0"), releasedDaysAgo = 5),
            input("old", listOf("1.0"), releasedDaysAgo = 400),
            input("unknown", listOf("1.0"), releasedDaysAgo = null),
        )
        assertEquals(listOf("g:unknown", "g:old", "g:new"), entries.map { it.label })
    }
}
