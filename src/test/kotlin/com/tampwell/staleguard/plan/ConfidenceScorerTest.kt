package com.tampwell.staleguard.plan

import com.tampwell.staleguard.version.UpgradeSeverity
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfidenceScorerTest {

    private fun days(n: Long) = TimeUnit.DAYS.toMillis(n)

    @Test
    fun `mature patch scores high`() {
        val c = ConfidenceScorer.score(UpgradeSeverity.PATCH, days(60), abandoned = false)
        assertEquals(85, c.score)
    }

    @Test
    fun `brand-new major scores low`() {
        val c = ConfidenceScorer.score(UpgradeSeverity.MAJOR, days(2), abandoned = false)
        assertEquals(20, c.score)
    }

    @Test
    fun `abandoned patch is dragged down`() {
        val c = ConfidenceScorer.score(UpgradeSeverity.PATCH, days(3 * 365), abandoned = true)
        assertEquals(50, c.score) // 50 +25 -5 -20
        assertTrue(c.factors.any { it.bundleKey == "confidence.factor.abandoned" })
    }

    @Test
    fun `unknown age never scores as safe as known-mature`() {
        val unknown = ConfidenceScorer.score(UpgradeSeverity.PATCH, null, abandoned = false)
        val mature = ConfidenceScorer.score(UpgradeSeverity.PATCH, days(60), abandoned = false)
        assertTrue(unknown.score < mature.score)
    }

    @Test
    fun `score is clamped to 0-100`() {
        val worst = ConfidenceScorer.score(UpgradeSeverity.MAJOR, days(1), abandoned = true)
        assertTrue(worst.score in 0..100)
        assertEquals(0, worst.score) // 50 -15 -15 -20 = 0
    }

    @Test
    fun `every branch contributes a factor`() {
        val c = ConfidenceScorer.score(UpgradeSeverity.MINOR, days(30), abandoned = true)
        assertEquals(3, c.factors.size)
        assertEquals(c.score, 50 + c.factors.sumOf { it.impact })
    }

    @Test
    fun `qualifier upgrades sit between patch and major`() {
        val patch = ConfidenceScorer.score(UpgradeSeverity.PATCH, days(30), false).score
        val qualifier = ConfidenceScorer.score(UpgradeSeverity.QUALIFIER, days(30), false).score
        val major = ConfidenceScorer.score(UpgradeSeverity.MAJOR, days(30), false).score
        assertTrue(patch > qualifier)
        assertTrue(qualifier > major)
    }

    @Test
    fun `a vulnerability fix earns a confidence bonus with its own factor`() {
        val base = ConfidenceScorer.score(UpgradeSeverity.MAJOR, null, abandoned = false)
        val vuln = ConfidenceScorer.score(UpgradeSeverity.MAJOR, null, abandoned = false, vulnerable = true)
        assertEquals(base.score + 20, vuln.score)
        assertTrue(vuln.factors.any { it.bundleKey == "confidence.factor.vulnerable" && it.impact == 20 })
        assertTrue(base.factors.none { it.bundleKey == "confidence.factor.vulnerable" })
    }
}
