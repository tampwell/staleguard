package com.tampwell.staleguard.impact

import com.tampwell.staleguard.plan.ConfidenceScorer
import com.tampwell.staleguard.plan.MeasuredImpact
import com.tampwell.staleguard.version.UpgradeSeverity
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfidenceWithMeasuredImpactTest {

    private val sixMonths = TimeUnit.DAYS.toMillis(180)

    private fun score(severity: UpgradeSeverity, measured: MeasuredImpact) =
        ConfidenceScorer.score(severity, sixMonths, abandoned = false, vulnerable = false, measured = measured)

    @Test
    fun `an unmeasured upgrade scores exactly as it did before the feature existed`() {
        val withDefault = ConfidenceScorer.score(UpgradeSeverity.PATCH, sixMonths, abandoned = false)
        val explicitlyUnknown = score(UpgradeSeverity.PATCH, MeasuredImpact.Unknown)

        assertEquals(withDefault.score, explicitlyUnknown.score)
        assertEquals(withDefault.factors, explicitlyUnknown.factors)
    }

    @Test
    fun `a clean measurement raises confidence above the same unmeasured upgrade`() {
        val unknown = score(UpgradeSeverity.MINOR, MeasuredImpact.Unknown)
        val clean = score(UpgradeSeverity.MINOR, MeasuredImpact.Clean)

        assertTrue("clean ${clean.score} should beat unknown ${unknown.score}", clean.score > unknown.score)
        assertTrue(clean.factors.any { it.bundleKey == "confidence.factor.impact.clean" })
    }

    @Test
    fun `a measured break caps the score however safe the upgrade looks otherwise`() {
        // A recent patch release is the most confident case the heuristics can
        // produce. Knowing it deletes something this project calls has to win.
        val safestPossible = score(UpgradeSeverity.PATCH, MeasuredImpact.Unknown)
        val broken = score(UpgradeSeverity.PATCH, MeasuredImpact.Breaks(members = 1, callSites = 1))

        assertTrue("expected a high baseline, got ${safestPossible.score}", safestPossible.score >= 80)
        assertTrue(
            "a known break must not score above ${ConfidenceScorer.BREAKS_CEILING}, got ${broken.score}",
            broken.score <= ConfidenceScorer.BREAKS_CEILING,
        )
    }

    @Test
    fun `a vulnerable current version cannot argue a known break back up`() {
        // The CVE bonus is the strongest positive in the model; the ceiling
        // still applies, because shipping code that does not compile is not a
        // fix for shipping a CVE.
        val broken = ConfidenceScorer.score(
            UpgradeSeverity.PATCH, sixMonths, abandoned = false, vulnerable = true,
            measured = MeasuredImpact.Breaks(members = 3, callSites = 9),
        )

        assertTrue(broken.score <= ConfidenceScorer.BREAKS_CEILING)
        assertTrue(broken.factors.any { it.bundleKey == "confidence.factor.impact.breaks" })
    }

    @Test
    fun `the score never leaves the zero to one hundred range`() {
        for (severity in UpgradeSeverity.entries) {
            for (measured in listOf(MeasuredImpact.Unknown, MeasuredImpact.Clean, MeasuredImpact.Breaks(9, 40))) {
                val value = score(severity, measured).score
                assertTrue("$severity/$measured produced $value", value in 0..100)
            }
        }
    }
}

class ImpactMemoryRecordingTest {

    private fun report(
        usages: List<RemovedUsage> = emptyList(),
        removedTotal: Int = 0,
        incomplete: ImpactReport.Incomplete? = null,
        truncated: Boolean = false,
        rehearsal: ImpactReport.Rehearsal? = null,
    ) = ImpactReport("g:a", "1.0", "2.0", removedTotal, usages, incomplete, truncated, rehearsal)

    private val usage = RemovedUsage(
        MemberRef("a/B", "gone", "()V"),
        listOf(
            UsageLocation("file:///x/Y.java", "src/Y.java", 3, 40),
            UsageLocation("file:///x/Y.java", "src/Y.java", 9, 120),
        ),
    )

    private fun classify(report: ImpactReport) = ImpactMemory.classify(report)

    @Test
    fun `a finished analysis with no usages is remembered as clean`() {
        assertEquals(MeasuredImpact.Clean, classify(report(removedTotal = 197)))
    }

    @Test
    fun `usages are remembered with both counts`() {
        assertEquals(MeasuredImpact.Breaks(1, 2), classify(report(usages = listOf(usage), removedTotal = 197)))
    }

    @Test
    fun `an analysis that could not finish claims nothing`() {
        assertEquals(
            MeasuredImpact.Unknown,
            classify(report(incomplete = ImpactReport.Incomplete.CANDIDATE_JAR_UNAVAILABLE)),
        )
    }

    @Test
    fun `a truncated search claims nothing, because absence of findings proves nothing there`() {
        assertEquals(MeasuredImpact.Unknown, classify(report(removedTotal = 90000, truncated = true)))
    }

    @Test
    fun `a rehearsal that introduces problems downgrades an otherwise clean verdict`() {
        val rehearsal = ImpactReport.Rehearsal(fixed = emptyList(), introduced = listOf("x.jar: y.Z#gone()"))

        assertEquals(MeasuredImpact.BreaksLinkage(1), classify(report(removedTotal = 5, rehearsal = rehearsal)))
    }

    @Test
    fun `my own broken calls outrank the rehearsal in the verdict`() {
        val rehearsal = ImpactReport.Rehearsal(fixed = emptyList(), introduced = listOf("x.jar: y.Z#gone()"))

        assertEquals(
            MeasuredImpact.Breaks(1, 2),
            classify(report(usages = listOf(usage), rehearsal = rehearsal)),
        )
    }

    @Test
    fun `a rehearsal that only fixes problems stays clean`() {
        val rehearsal = ImpactReport.Rehearsal(fixed = listOf("x.jar: y.Z#gone()"), introduced = emptyList())

        assertEquals(MeasuredImpact.Clean, classify(report(rehearsal = rehearsal)))
    }
}
