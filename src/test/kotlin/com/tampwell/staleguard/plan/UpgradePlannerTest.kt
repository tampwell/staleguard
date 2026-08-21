package com.tampwell.staleguard.plan

import com.tampwell.staleguard.inspection.FixTarget
import com.tampwell.staleguard.model.DeclaredDependency
import com.tampwell.staleguard.repository.ArtifactVersions
import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.version.MavenVersion
import com.tampwell.staleguard.version.UpgradeSeverity
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpgradePlannerTest {

    private val now = 1_800_000_000_000L
    private val twoYears = TimeUnit.DAYS.toMillis(2 * 365)

    private fun declared(
        group: String = "com.example",
        artifact: String = "lib",
        raw: String? = "1.0.0",
        resolved: String? = raw,
    ) = DeclaredDependency(group, artifact, raw, resolved, DeclaredDependency.Origin.DEPENDENCIES)

    private fun known(
        versions: List<String>,
        releasedDaysAgo: Long? = 30,
        group: String = "com.example",
        artifact: String = "lib",
    ) = ArtifactVersions(
        coordinates = Coordinates(group, artifact),
        versions = versions.map(::MavenVersion),
        newestReleaseAtMillis = releasedDaysAgo?.let { now - TimeUnit.DAYS.toMillis(it) },
        stale = false,
    )

    private fun plan(vararg inputs: PlannerInput, prereleases: Boolean = false) =
        UpgradePlanner.plan(inputs.toList(), prereleases, twoYears, { _, _ -> false }, now)

    @Test
    fun `literal upgrade becomes a candidate with severity and recommendation`() {
        val result = plan(PlannerInput("app", declared(raw = "1.0.0"), known(listOf("1.0.0", "1.0.1"))))
        val candidate = result.candidates.single()
        assertEquals("1.0.1", candidate.suggestedVersion.value)
        assertEquals(UpgradeSeverity.PATCH, candidate.severity)
        assertEquals(FixTarget.Literal, candidate.target)
        assertEquals(Recommendation.SAFE, candidate.recommendation)
    }

    @Test
    fun `up-to-date dependency produces no candidate`() {
        val result = plan(PlannerInput("app", declared(raw = "2.0"), known(listOf("1.0", "2.0"))))
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun `unresolved dependency produces no candidate`() {
        val result = plan(PlannerInput("app", declared(), null))
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun `managed dependency - null raw version - is never a candidate`() {
        val result = plan(PlannerInput("app", declared(raw = null, resolved = null), known(listOf("9.9"))))
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun `ignored coordinates are excluded`() {
        val result = UpgradePlanner.plan(
            listOf(PlannerInput("app", declared(), known(listOf("1.0.0", "1.0.1")))),
            suggestPrereleases = false,
            abandonmentThresholdMillis = twoYears,
            ignored = { g, a -> g == "com.example" && a == "lib" },
            nowMillis = now,
        )
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun `prerelease toggle switches suggestion`() {
        val input = PlannerInput("app", declared(raw = "1.0"), known(listOf("1.0", "1.1", "2.0-rc1")))
        assertEquals("1.1", plan(input).candidates.single().suggestedVersion.value)
        assertEquals("2.0-rc1", plan(input, prereleases = true).candidates.single().suggestedVersion.value)
    }

    @Test
    fun `property-versioned candidate carries the property target`() {
        val result = plan(
            PlannerInput("app", declared(raw = "\${lib.version}", resolved = "1.0.0"), known(listOf("1.0.0", "1.2.0"))),
        )
        val candidate = result.candidates.single()
        assertEquals("lib.version", candidate.propertyName)
        assertEquals(UpgradeSeverity.MINOR, candidate.severity)
    }

    @Test
    fun `property usage counts every referencing dependency across modules`() {
        val result = plan(
            PlannerInput("app", declared(artifact = "a", raw = "\${x.version}", resolved = "1.0"), known(listOf("1.0", "1.1"), artifact = "a")),
            PlannerInput("lib", declared(artifact = "b", raw = "\${x.version}", resolved = "1.0"), null),
            PlannerInput("lib", declared(artifact = "c", raw = "\${x.version}.RELEASE", resolved = "1.0.RELEASE"), null),
            PlannerInput("lib", declared(artifact = "d", raw = "2.0"), null),
        )
        assertEquals(3, result.impactOf("x.version"))
        assertEquals(0, result.impactOf("unused.version"))
    }

    @Test
    fun `abandoned dependency patch upgrade is STALE, not falsely breaking`() {
        val result = plan(
            PlannerInput("app", declared(raw = "1.0.0"), known(listOf("1.0.0", "1.0.1"), releasedDaysAgo = 3 * 365)),
        )
        assertEquals(Recommendation.STALE, result.candidates.single().recommendation)
    }

    @Test
    fun `major upgrade is BREAKING regardless of age`() {
        val result = plan(PlannerInput("app", declared(raw = "1.0"), known(listOf("1.0", "2.0"), releasedDaysAgo = 10)))
        assertEquals(Recommendation.BREAKING, result.candidates.single().recommendation)
    }

    @Test
    fun `fresh minor is REVIEW`() {
        val result = plan(PlannerInput("app", declared(raw = "1.0"), known(listOf("1.0", "1.1"), releasedDaysAgo = 30)))
        assertEquals(Recommendation.REVIEW, result.candidates.single().recommendation)
    }

    @Test
    fun `unknown release date never yields SAFE`() {
        val result = plan(
            PlannerInput("app", declared(raw = "1.0.0"), known(listOf("1.0.0", "1.0.1"), releasedDaysAgo = null)),
        )
        assertEquals(Recommendation.REVIEW, result.candidates.single().recommendation)
    }

    @Test
    fun `version pin caps the candidate inside the ceiling`() {
        val pin = com.tampwell.staleguard.policy.VersionPin(
            "com.example:lib", com.tampwell.staleguard.version.VersionConstraint.parse("2.*")!!,
        )
        val result = UpgradePlanner.plan(
            listOf(PlannerInput("app", declared(raw = "2.0.0"), known(listOf("2.0.0", "2.5.0", "3.0.0")))),
            suggestPrereleases = false,
            abandonmentThresholdMillis = twoYears,
            ignored = { _, _ -> false },
            nowMillis = now,
            versionAllowed = { g, a, v -> !pin.appliesTo(g, a) || pin.allows(v) },
        )
        assertEquals("2.5.0", result.candidates.single().suggestedVersion.value)
    }

    @Test
    fun `pin with nothing newer in range removes the candidate entirely`() {
        val pin = com.tampwell.staleguard.policy.VersionPin(
            "com.example:lib", com.tampwell.staleguard.version.VersionConstraint.parse("<3")!!,
        )
        val result = UpgradePlanner.plan(
            listOf(PlannerInput("app", declared(raw = "2.9.0"), known(listOf("2.9.0", "3.0.0", "3.5.0")))),
            suggestPrereleases = false,
            abandonmentThresholdMillis = twoYears,
            ignored = { _, _ -> false },
            nowMillis = now,
            versionAllowed = { g, a, v -> !pin.appliesTo(g, a) || pin.allows(v) },
        )
        assertTrue(result.candidates.isEmpty())
    }
}
