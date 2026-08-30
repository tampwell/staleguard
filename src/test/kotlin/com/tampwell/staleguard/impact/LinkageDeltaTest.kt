package com.tampwell.staleguard.impact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkageDeltaTest {

    private fun broken(fromJar: String, member: String) = LinkageAudit.BrokenRef(
        fromJar,
        MemberRef("lib/Api", member, "()V"),
        "lib-1.0.jar",
    )

    private fun report(
        brokenMembers: List<LinkageAudit.BrokenRef> = emptyList(),
        evicted: List<LinkageAudit.EvictedClassRefs> = emptyList(),
    ) = LinkageAudit.Report(2, 10, 100, brokenMembers, evicted)

    @Test
    fun `identical findings are not news`() {
        val current = report(brokenMembers = listOf(broken("app.jar", "gone")))
        val baseline = LinkageDelta.fingerprint(current)

        assertFalse(LinkageDelta.newSince(baseline, current).isNews)
    }

    @Test
    fun `a finding absent last time is news`() {
        val baseline = LinkageDelta.fingerprint(report(brokenMembers = listOf(broken("app.jar", "old"))))
        val current = report(brokenMembers = listOf(broken("app.jar", "old"), broken("app.jar", "introduced")))

        val delta = LinkageDelta.newSince(baseline, current)

        assertTrue(delta.isNews)
        assertEquals(1, delta.count)
        assertEquals("introduced", delta.newBroken.single().ref.name)
    }

    @Test
    fun `findings that disappear are not news, and the next baseline forgets them`() {
        val baseline = LinkageDelta.fingerprint(report(brokenMembers = listOf(broken("app.jar", "fixed"))))
        val current = report()

        assertFalse(LinkageDelta.newSince(baseline, current).isNews)
        // Re-fingerprinting the clean report forgets the fixed finding, so it
        // would correctly be news again if a later sync reintroduced it.
        assertTrue(LinkageDelta.fingerprint(current).isEmpty())
    }

    @Test
    fun `more call sites for a known break stay quiet, a new member does not`() {
        // Identity is what broke, not how many places hit it: extra call
        // sites for a known break must not re-notify.
        val one = broken("app.jar", "gone")
        val baseline = LinkageDelta.fingerprint(report(brokenMembers = listOf(one)))
        val current = report(brokenMembers = listOf(one, one.copy()))

        assertFalse(LinkageDelta.newSince(baseline, current).isNews)
    }

    @Test
    fun `evicted classes carry their own identity`() {
        val evicted = LinkageAudit.EvictedClassRefs("app.jar", "lib/NewThing", 5)
        val baseline = LinkageDelta.fingerprint(report())

        val delta = LinkageDelta.newSince(baseline, report(evicted = listOf(evicted)))

        assertTrue(delta.isNews)
        assertEquals("lib/NewThing", delta.newEvicted.single().owner)
    }

    @Test
    fun `the same break from a different caller jar is separate news`() {
        val baseline = LinkageDelta.fingerprint(report(brokenMembers = listOf(broken("app.jar", "gone"))))
        val current = report(brokenMembers = listOf(broken("app.jar", "gone"), broken("web.jar", "gone")))

        val delta = LinkageDelta.newSince(baseline, current)

        assertEquals(1, delta.count)
        assertEquals("web.jar", delta.newBroken.single().fromJar)
    }

    @Test
    fun `a new shadow group is news, but more classes inside a known group are not`() {
        val known = ShadowAudit.ShadowGroup("a.jar", listOf("b.jar"), 2, listOf("lib.X"))
        val baseline = LinkageDelta.fingerprint(report().copy(shadowedGroups = listOf(known)))

        val grown = known.copy(classCount = 7, examples = listOf("lib.X", "lib.Y"))
        assertFalse(LinkageDelta.newSince(baseline, report().copy(shadowedGroups = listOf(grown))).isNews)

        val fresh = ShadowAudit.ShadowGroup("a.jar", listOf("c.jar"), 1, listOf("lib.Z"))
        val delta = LinkageDelta.newSince(baseline, report().copy(shadowedGroups = listOf(grown, fresh)))
        assertTrue(delta.isNews)
        assertEquals(listOf(fresh), delta.newShadowed)
    }

    @Test
    fun `duplicate new findings collapse to one notification-worthy entry`() {
        val baseline = LinkageDelta.fingerprint(report())
        val dup = broken("app.jar", "gone")

        assertEquals(1, LinkageDelta.newSince(baseline, report(brokenMembers = listOf(dup, dup.copy()))).count)
    }
}
