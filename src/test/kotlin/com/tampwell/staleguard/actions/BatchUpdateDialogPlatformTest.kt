package com.tampwell.staleguard.actions

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.tampwell.staleguard.plan.ConfidenceScorer
import com.tampwell.staleguard.plan.Recommendation
import com.tampwell.staleguard.plan.UpgradeCandidate
import com.tampwell.staleguard.plan.UpgradePlan
import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.inspection.FixTarget
import com.tampwell.staleguard.version.MavenVersion
import com.tampwell.staleguard.version.UpgradeSeverity

/**
 * Constructs the real dialog against the platform fixture, which is what
 * catches wiring mistakes — a bad bundle key, a broken action array, a layout
 * crash — without a hand-driven IDE session. The headless test framework
 * builds DialogWrapper UIs without showing them.
 */
class BatchUpdateDialogPlatformTest : BasePlatformTestCase() {

    private fun candidate(severity: UpgradeSeverity, artifact: String) = UpgradeCandidate(
        moduleName = "app",
        coordinates = Coordinates("com.example", artifact),
        currentVersion = MavenVersion("1.0.0"),
        suggestedVersion = MavenVersion("2.0.0"),
        severity = severity,
        target = FixTarget.Literal,
        recommendation = Recommendation.REVIEW,
        moduleId = "app",
        confidence = ConfidenceScorer.score(severity, null, abandoned = false),
    )

    // Construction runs init(), which builds the panel, the row map, and the
    // action arrays — a bad bundle key, broken action, or layout crash all
    // surface here without a hand-driven IDE session.
    fun `test the dialog constructs headlessly and preselects patch rows only`() {
        val plan = UpgradePlan(
            listOf(candidate(UpgradeSeverity.PATCH, "alpha"), candidate(UpgradeSeverity.MAJOR, "beta")),
            emptyMap(),
        )
        val dialog = BatchUpdateDialog(project, plan)
        try {
            assertEquals(
                listOf("com.example:alpha"),
                dialog.selectedCandidates().map { it.coordinates.toString() },
            )
        } finally {
            runCatching { com.intellij.openapi.util.Disposer.dispose(dialog.disposable) }
        }
    }

    fun `test a security fix is preselected even behind a major bump`() {
        val urgent = candidate(UpgradeSeverity.MAJOR, "vulnerable")
            .copy(recommendation = Recommendation.URGENT)
        val dialog = BatchUpdateDialog(project, UpgradePlan(listOf(urgent), emptyMap()))
        try {
            assertEquals(1, dialog.selectedCandidates().size)
        } finally {
            runCatching { com.intellij.openapi.util.Disposer.dispose(dialog.disposable) }
        }
    }
}
