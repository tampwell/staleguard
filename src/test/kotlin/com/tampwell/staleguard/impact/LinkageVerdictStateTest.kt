package com.tampwell.staleguard.impact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkageVerdictStateTest {

    @Test
    fun `failing counts broken and evicted together, shadowed stays its own number`() {
        val report = LinkageAudit.Report(
            jarCount = 3,
            classCount = 10,
            refCount = 100,
            brokenMembers = listOf(
                LinkageAudit.BrokenRef("a.jar", MemberRef("lib/Api", "gone", "()V"), "lib.jar"),
                LinkageAudit.BrokenRef("b.jar", MemberRef("lib/Api", "gone2", "()V"), "lib.jar"),
            ),
            evictedClasses = listOf(LinkageAudit.EvictedClassRefs("a.jar", "lib/Missing", 4)),
            shadowedGroups = listOf(ShadowAudit.ShadowGroup("x.jar", listOf("y.jar"), 5, listOf("lib.X"))),
        )

        val verdict = LinkageVerdictState.verdictOf(report, asOfMillis = 42L)

        assertEquals(3, verdict.failing)
        assertEquals(1, verdict.shadowed)
        assertEquals(42L, verdict.asOfMillis)
        assertFalse(verdict.clean)
    }

    @Test
    fun `an empty report is a clean verdict`() {
        val report = LinkageAudit.Report(1, 1, 1, emptyList(), emptyList())

        assertTrue(LinkageVerdictState.verdictOf(report, 0L).clean)
    }

    @Test
    fun `problems map broken jars to coordinates with callers and the fix`() {
        val report = LinkageAudit.Report(
            jarCount = 3, classCount = 10, refCount = 100,
            brokenMembers = listOf(
                LinkageAudit.BrokenRef("caller-a.jar", MemberRef("lib/Api", "gone", "()V"), "lib-1.0.jar"),
                LinkageAudit.BrokenRef("caller-b.jar", MemberRef("lib/Api", "gone", "()V"), "lib-1.0.jar"),
                LinkageAudit.BrokenRef("caller-a.jar", MemberRef("x/Y", "z", "()V"), ownerJar = null),
            ),
            evictedClasses = emptyList(),
        )
        val coordinates = com.tampwell.staleguard.repository.Coordinates("com.lib", "lib")

        val problems = LinkageVerdictState.problemsOf(
            report,
            identify = { jarName -> coordinates.takeIf { jarName == "lib-1.0.jar" } },
            fixFor = { "2.0.0" },
        )

        val problem = problems.getValue(coordinates)
        assertEquals(2, problem.brokenCalls)
        assertEquals(listOf("caller-a.jar", "caller-b.jar"), problem.callers)
        assertEquals("2.0.0", problem.fixVersion)
    }

    @Test
    fun `an unidentifiable jar never becomes an editor warning`() {
        val report = LinkageAudit.Report(
            jarCount = 1, classCount = 1, refCount = 1,
            brokenMembers = listOf(
                LinkageAudit.BrokenRef("caller.jar", MemberRef("lib/Api", "gone", "()V"), "mystery.jar"),
            ),
            evictedClasses = emptyList(),
        )

        assertTrue(LinkageVerdictState.problemsOf(report, identify = { null }, fixFor = { null }).isEmpty())
    }
}
