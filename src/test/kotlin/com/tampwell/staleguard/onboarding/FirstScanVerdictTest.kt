package com.tampwell.staleguard.onboarding

import com.tampwell.staleguard.onboarding.FirstScanVerdict.Verdict
import com.tampwell.staleguard.plan.ModuleStats
import org.junit.Assert.assertEquals
import org.junit.Test

class FirstScanVerdictTest {

    private fun summary(
        total: Int = 10,
        unresolved: Int = 0,
        patch: Int = 0,
        major: Int = 0,
        abandoned: Int = 0,
        vulnerable: Int = 0,
    ) = ModuleStats("", total, unresolved, patch, 0, major, 0, abandoned, vulnerable)

    @Test
    fun `a fully current project is the one case worth a word`() {
        assertEquals(Verdict.NOTIFY_CLEAN, FirstScanVerdict.of(summary(), pendingLookups = false))
    }

    @Test
    fun `findings stay silent because the warnings already show them`() {
        assertEquals(Verdict.STAY_SILENT, FirstScanVerdict.of(summary(patch = 1), pendingLookups = false))
        assertEquals(Verdict.STAY_SILENT, FirstScanVerdict.of(summary(major = 3), pendingLookups = false))
        assertEquals(Verdict.STAY_SILENT, FirstScanVerdict.of(summary(abandoned = 1), pendingLookups = false))
        assertEquals(Verdict.STAY_SILENT, FirstScanVerdict.of(summary(vulnerable = 1), pendingLookups = false))
    }

    @Test
    fun `never claims everything is current while lookups are in flight`() {
        assertEquals(Verdict.WAIT, FirstScanVerdict.of(summary(), pendingLookups = true))
    }

    @Test
    fun `never claims everything is current while coordinates are unresolved`() {
        assertEquals(Verdict.WAIT, FirstScanVerdict.of(summary(unresolved = 2), pendingLookups = false))
    }

    @Test
    fun `a project with no dependencies gets no claim either way`() {
        assertEquals(Verdict.WAIT, FirstScanVerdict.of(summary(total = 0), pendingLookups = false))
        assertEquals(Verdict.WAIT, FirstScanVerdict.of(null, pendingLookups = false))
    }
}
