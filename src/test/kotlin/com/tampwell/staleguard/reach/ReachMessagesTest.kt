package com.tampwell.staleguard.reach

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Wording and persistence. Reasons are looked up by enum name, which the
 * bundle-completeness check cannot see statically, so every reason is
 * checked here.
 */
class ReachMessagesTest : BasePlatformTestCase() {

    fun `test every undetermined reason has wording`() {
        for (reason in ReachVerdict.Reason.entries) {
            val text = ReachMessages.reason(reason)
            assertFalse("missing wording for $reason: $text", text.isBlank() || text.contains("reach.reason"))
        }
    }

    fun `test rows lead with what needs attention`() {
        val ordered = listOf(
            ReachVerdict.NotReached(3),
            ReachVerdict.Unknown(ReachVerdict.Reason.NOT_BUILT),
            ReachVerdict.Reached(inProduction = false, path = listOf("T.test"), touchedReached = 1, touchedTotal = 1),
            ReachVerdict.Reached(inProduction = true, path = listOf("App.run"), touchedReached = 1, touchedTotal = 1),
        ).sortedBy(ReachMessages::rank)

        assertTrue((ordered[0] as ReachVerdict.Reached).inProduction)
        assertFalse((ordered[1] as ReachVerdict.Reached).inProduction)
        assertTrue(ordered[2] is ReachVerdict.Unknown)
        assertTrue(ordered[3] is ReachVerdict.NotReached)
    }

    fun `test the summary names each outcome`() {
        val text = CheckReachabilityAction.summaryText(
            ReachabilityService.Summary(
                vulnerable = 4, reached = 1, reachedInTestsOnly = 1, notReached = 1, unknown = 1,
                notYetLookedUp = 2, notBuilt = false,
            ),
        )

        assertTrue(text, text.contains("4 vulnerable artifacts") && text.contains("1 reached from your code"))
        assertTrue(text, text.contains("2 artifacts have no vulnerability data yet"))
    }

    fun `test verdicts survive the workspace round trip, in full`() {
        val state = ReachabilityState()
        val reached = ReachVerdict.Reached(true, listOf("App.handle", "JndiLookup.lookup"), 1, 2)
        val key = ReachabilityState.Key("g", "a", "1.0", "GHSA-1")
        val other = ReachabilityState.Key("g", "b", "2.0", "GHSA-2")
        state.record(
            mapOf(key to reached, other to ReachVerdict.Unknown(ReachVerdict.Reason.FIX_NOT_FETCHED)),
            nowMillis = 42,
        )

        val reloaded = ReachabilityState()
        reloaded.loadState(state.state)

        assertEquals(42L, reloaded.asOfMillis)
        assertEquals(reached, reloaded.verdicts[key])
        assertEquals(ReachVerdict.Unknown(ReachVerdict.Reason.FIX_NOT_FETCHED), reloaded.verdicts[other])

        // A loaded file replaces everything; nothing from before survives.
        reloaded.loadState(ReachabilityState.Bean())
        assertTrue(reloaded.verdicts.isEmpty())
    }
}
