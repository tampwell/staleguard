package com.tampwell.staleguard.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.plan.UpgradeCandidate
import com.tampwell.staleguard.plan.UpgradePlan
import com.tampwell.staleguard.version.UpgradeSeverity
import javax.swing.JComponent

/**
 * The batch-update preview: every available upgrade, grouped by severity,
 * each row a checkbox showing the exact transition and the recommendation.
 * PATCH rows are pre-selected (the safe default); each group has a
 * select-all toggle. Nothing is written until OK — this dialog IS the
 * preview of exactly what will change.
 */
class BatchUpdateDialog(private val project: Project, private val plan: UpgradePlan) : DialogWrapper(project) {

    private val rows = linkedMapOf<JBCheckBox, UpgradeCandidate>()

    init {
        title = StaleguardBundle.message("batch.dialog.title")
        setOKButtonText(StaleguardBundle.message("batch.dialog.apply"))
        init()
    }

    override fun createLeftSideActions(): Array<javax.swing.Action> = arrayOf(checkImpactAction())

    /**
     * Runs the binary comparison for every selected row, then writes the
     * verdict into the row and DESELECTS anything measured as breaking. The
     * whole point of this dialog is bulk-apply, and bulk-apply must not be
     * able to silently include an upgrade known to break the build.
     */
    private fun checkImpactAction(): javax.swing.Action = object : DialogWrapperAction(
        StaleguardBundle.message("batch.impact.button"),
    ) {
        override fun doAction(e: java.awt.event.ActionEvent) {
            if (com.intellij.openapi.project.DumbService.isDumb(project)) {
                setErrorText(StaleguardBundle.message("impact.indexing"))
                return
            }
            // Plugin markers resolve to no jar; there is nothing to compare.
            val targets = rows.filter { (box, candidate) ->
                box.isSelected && !candidate.coordinates.artifactId.endsWith(".gradle.plugin")
            }
            if (targets.isEmpty()) return
            setErrorText(null)

            val service = com.tampwell.staleguard.impact.UpgradeImpactService.getInstance(project)
            val results = linkedMapOf<JBCheckBox, com.tampwell.staleguard.plan.MeasuredImpact>()
            val completed = com.intellij.openapi.progress.ProgressManager.getInstance()
                .runProcessWithProgressSynchronously(
                    {
                        val indicator = com.intellij.openapi.progress.ProgressManager.getInstance().progressIndicator
                        indicator.isIndeterminate = false
                        for ((index, entry) in targets.entries.withIndex()) {
                            val (box, candidate) = entry
                            indicator.checkCanceled()
                            indicator.fraction = index.toDouble() / targets.size
                            indicator.text = candidate.coordinates.toString()
                            val report = service.analyze(
                                candidate.coordinates,
                                candidate.currentVersion.value,
                                candidate.suggestedVersion.value,
                                indicator,
                            )
                            results[box] = com.tampwell.staleguard.impact.ImpactMemory.classify(report)
                        }
                    },
                    StaleguardBundle.message("batch.impact.progress"),
                    true,
                    project,
                )

            // A cancelled run still applies what it finished: each verdict was
            // fully measured before the next began.
            for ((box, measured) in results) {
                when (measured) {
                    is com.tampwell.staleguard.plan.MeasuredImpact.Breaks -> {
                        box.isSelected = false
                        box.text = withVerdict(box.text, StaleguardBundle.message("toolwindow.impact.breaks", measured.members))
                    }
                    is com.tampwell.staleguard.plan.MeasuredImpact.BreaksLinkage -> {
                        box.isSelected = false
                        box.text = withVerdict(box.text, StaleguardBundle.message("toolwindow.impact.linkage", measured.problems))
                    }
                    com.tampwell.staleguard.plan.MeasuredImpact.Clean ->
                        box.text = withVerdict(box.text, StaleguardBundle.message("toolwindow.impact.clean"))
                    com.tampwell.staleguard.plan.MeasuredImpact.Unknown -> Unit
                }
            }
            if (!completed) setErrorText(StaleguardBundle.message("batch.impact.cancelled"))
        }
    }



    override fun createCenterPanel(): JComponent = panel {
        for (severity in DISPLAY_ORDER) {
            val group = plan.candidates
                .filter { it.severity == severity }
                .sortedWith(
                    compareByDescending<UpgradeCandidate> { it.confidence.score }
                        .thenBy { it.moduleName }
                        .thenBy { it.coordinates.toString() },
                )
            if (group.isEmpty()) continue

            group(StaleguardBundle.message("severity.${severity.name.lowercase()}")) {
                lateinit var groupBoxes: List<JBCheckBox>
                row {
                    val selectAll = JBCheckBox(
                        StaleguardBundle.message("batch.dialog.select.all"),
                        severity == UpgradeSeverity.PATCH,
                    )
                    selectAll.addActionListener {
                        groupBoxes.forEach { it.isSelected = selectAll.isSelected }
                    }
                    cell(selectAll)
                }
                groupBoxes = group.map { candidate ->
                    // Preselected: patches (routine) and security fixes (urgent) —
                    // a CVE fix should not hide unchecked behind a major bump.
                    val preselect = severity == UpgradeSeverity.PATCH ||
                        candidate.recommendation == com.tampwell.staleguard.plan.Recommendation.URGENT
                    val box = JBCheckBox(label(candidate), preselect)
                    row { cell(box) }
                    rows[box] = candidate
                    box
                }
            }
        }
    }

    private fun label(c: UpgradeCandidate): String {
        // Name the advisory driving an URGENT row — "update now" without a
        // CVE id reads as alarmism; with one it reads as a fact.
        val advisory = if (c.recommendation == com.tampwell.staleguard.plan.Recommendation.URGENT) {
            com.tampwell.staleguard.services.VulnerabilityService.getInstance()
                .peek(c.coordinates, c.currentVersion.value)?.advisories
                ?.maxByOrNull { it.severityRank }
                ?.let { "  [${it.displayId}]" }
                .orEmpty()
        } else {
            ""
        }
        val base = "${c.moduleName}: ${c.coordinates.displayName}  ${c.currentVersion.value} → ${c.suggestedVersion.value}" +
            "  [" + StaleguardBundle.message("confidence.label", c.confidence.score) + "]" +
            " — " + StaleguardBundle.message(c.recommendation.bundleKey) + advisory
        val property = c.propertyName ?: return base
        val impact = plan.impactOf(property)
        return if (impact > 1) {
            base + "  [" + StaleguardBundle.message("batch.property.impact", property, impact) + "]"
        } else {
            base
        }
    }

    fun selectedCandidates(): List<UpgradeCandidate> =
        rows.filterKeys { it.isSelected }.values.toList()

    companion object {
        /** Idempotent: re-checking replaces the old verdict instead of stacking a second one. */
        fun withVerdict(text: String, verdict: String): String =
            text.substringBefore("  [checked:").trimEnd() + "  " + verdict

        private val DISPLAY_ORDER = listOf(
            UpgradeSeverity.PATCH,
            UpgradeSeverity.MINOR,
            UpgradeSeverity.QUALIFIER,
            UpgradeSeverity.MAJOR,
        )
    }
}
