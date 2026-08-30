package com.tampwell.staleguard.impact

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.tampwell.staleguard.inspection.LinkageProblems
import com.tampwell.staleguard.repository.Coordinates

/**
 * The service half of the ambient verdict: what the audits record is what the
 * editor asks about, and what persists is what a reopened project loads.
 */
class LinkageVerdictStatePlatformTest : BasePlatformTestCase() {

    private val coordinates = Coordinates("com.fasterxml.jackson.core", "jackson-core")

    private fun brokenReport() = LinkageAudit.Report(
        jarCount = 2,
        classCount = 5,
        refCount = 50,
        brokenMembers = listOf(
            LinkageAudit.BrokenRef("jackson-databind-2.19.0.jar", MemberRef("c/j/JsonParser", "streamReadConstraints", "()V"), "jackson-core-2.13.0.jar"),
        ),
        evictedClasses = emptyList(),
    )

    private fun record() {
        LinkageVerdictState.getInstance(project).record(
            brokenReport(),
            identify = { jarName ->
                JarCoordinates.Identified(coordinates, "2.13.0").takeIf { jarName == "jackson-core-2.13.0.jar" }
            },
            fixFor = { "2.15.0" },
        )
    }

    fun `test the editor query is version exact`() {
        record()

        val hit = LinkageProblems.problemFor(project, coordinates, "2.13.0")
        assertNotNull(hit)
        assertEquals("2.15.0", checkNotNull(hit).fixVersion)

        // The user bumped the declaration: the warning must stop matching
        // immediately, not after the next audit.
        assertNull(LinkageProblems.problemFor(project, coordinates, "2.15.0"))
        assertNull(LinkageProblems.problemFor(project, coordinates, null))
        assertNull(LinkageProblems.problemFor(project, Coordinates("other", "artifact"), "2.13.0"))
    }

    fun `test the verdict and problems survive the persistence round trip`() {
        record()
        val state = LinkageVerdictState.getInstance(project)
        val before = checkNotNull(state.current)
        val beforeProblems = state.problems

        // What a reopened project would do: load the serialized bean back.
        state.loadState(state.state)

        assertEquals(before, state.current)
        assertEquals(beforeProblems, state.problems)
        assertEquals("2.13.0", state.problems.getValue(coordinates).version)
    }

    fun `test an empty state loads as no verdict at all`() {
        val state = LinkageVerdictState.getInstance(project)
        state.loadState(LinkageVerdictState.Bean())

        assertNull(state.current)
        assertTrue(state.problems.isEmpty())
    }
}
