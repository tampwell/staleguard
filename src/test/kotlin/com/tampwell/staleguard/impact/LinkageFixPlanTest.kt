package com.tampwell.staleguard.impact

import com.tampwell.staleguard.inspection.FixTarget
import com.tampwell.staleguard.model.DeclaredDependency
import com.tampwell.staleguard.plan.PlannerInput
import com.tampwell.staleguard.repository.Coordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkageFixPlanTest {

    private fun input(
        groupId: String,
        artifactId: String,
        rawVersion: String?,
        resolvedVersion: String? = rawVersion,
    ) = PlannerInput(
        moduleName = "app",
        declared = DeclaredDependency(groupId, artifactId, rawVersion, resolvedVersion, DeclaredDependency.Origin.DEPENDENCIES),
        known = null,
        moduleId = "pom.xml",
    )

    private val fixes = mapOf<String, FixSuggestions.Suggestion>(
        "jackson-core-2.13.0.jar" to FixSuggestions.Suggestion.FixedIn("2.15.0"),
    )

    private val identified = JarCoordinates.Identified(Coordinates("com.fasterxml.jackson.core", "jackson-core"), "2.13.0")

    @Test
    fun `a direct literal declaration becomes a bump through the shared applier`() {
        val steps = LinkageFixPlan.plan(
            fixes,
            identify = { identified },
            inputs = listOf(input("com.fasterxml.jackson.core", "jackson-core", "2.13.0")),
            mavenBuild = true,
        )

        val bump = steps.single() as LinkageFixPlan.Step.Bump
        assertEquals(FixTarget.Literal, bump.candidate.target)
        assertEquals("2.15.0", bump.candidate.suggestedVersion.value)
        assertEquals("2.13.0", bump.candidate.currentVersion.value)
    }

    @Test
    fun `a property-controlled declaration bumps the property definition`() {
        val steps = LinkageFixPlan.plan(
            fixes,
            identify = { identified },
            inputs = listOf(input("com.fasterxml.jackson.core", "jackson-core", "\${jackson.version}", "2.13.0")),
            mavenBuild = true,
        )

        assertEquals(
            FixTarget.Property("jackson.version"),
            (steps.single() as LinkageFixPlan.Step.Bump).candidate.target,
        )
    }

    @Test
    fun `a transitive artifact in a Maven build becomes a dependencyManagement pin`() {
        val steps = LinkageFixPlan.plan(fixes, identify = { identified }, inputs = emptyList(), mavenBuild = true)

        val manage = steps.single() as LinkageFixPlan.Step.Manage
        assertEquals("jackson-core", manage.coordinates.artifactId)
        assertEquals("2.15.0", manage.version)
    }

    @Test
    fun `a parent-managed declaration is pinned like a transitive, never edited inline`() {
        val steps = LinkageFixPlan.plan(
            fixes,
            identify = { identified },
            inputs = listOf(input("com.fasterxml.jackson.core", "jackson-core", rawVersion = null, resolvedVersion = "2.13.0")),
            mavenBuild = true,
        )

        assertTrue(steps.single() is LinkageFixPlan.Step.Manage)
    }

    @Test
    fun `a transitive artifact in a Gradle build hands over the constraints block`() {
        val steps = LinkageFixPlan.plan(fixes, identify = { identified }, inputs = emptyList(), mavenBuild = false)

        val snippet = steps.single() as LinkageFixPlan.Step.Snippet
        assertTrue("constraints" in snippet.text)
        assertTrue("com.fasterxml.jackson.core:jackson-core:2.15.0" in snippet.text)
    }

    @Test
    fun `an unidentifiable jar is reported, never guessed at`() {
        val steps = LinkageFixPlan.plan(fixes, identify = { null }, inputs = emptyList(), mavenBuild = true)

        assertTrue(steps.single() is LinkageFixPlan.Step.Unappliable)
    }

    @Test
    fun `a declaration already at the fix version is not bumped backwards`() {
        val steps = LinkageFixPlan.plan(
            fixes,
            identify = { identified },
            inputs = listOf(input("com.fasterxml.jackson.core", "jackson-core", "2.16.0")),
            mavenBuild = true,
        )

        assertTrue(steps.single() is LinkageFixPlan.Step.Unappliable)
    }

    @Test
    fun `jars with no clean version produce no step`() {
        val steps = LinkageFixPlan.plan(
            mapOf("hopeless.jar" to FixSuggestions.Suggestion.NoCleanVersion),
            identify = { identified },
            inputs = emptyList(),
            mavenBuild = true,
        )

        assertTrue(steps.isEmpty())
    }
}
