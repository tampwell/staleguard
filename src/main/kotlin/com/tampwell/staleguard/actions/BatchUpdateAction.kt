package com.tampwell.staleguard.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.plan.UpgradePlanner
import com.tampwell.staleguard.settings.StaleguardSettings
import java.util.concurrent.TimeUnit

/**
 * Tools → "Staleguard: Update Dependencies…" — plans every available upgrade
 * from the warm cache, previews them in [BatchUpdateDialog], and applies the
 * selection in one undoable write command.
 *
 * Reads ONLY the warm cache (same invariant as the inspection): dependencies
 * that haven't been resolved yet simply don't appear; opening the pom files
 * or the Staleguard tool window first warms things up.
 */
class BatchUpdateAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        // Gradle-only projects have no Maven modules; the action stays
        // available and reports "nothing to update" when the cache is cold.
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = StaleguardSettings.getInstance()

        val plan = UpgradePlanner.plan(
            inputs = UpgradeApplier.collectInputs(project),
            suggestPrereleases = settings.state.suggestPrereleases,
            abandonmentThresholdMillis = TimeUnit.DAYS.toMillis(365L * settings.state.abandonmentYears),
            ignored = com.tampwell.staleguard.policy.ProjectPolicyService.getInstance(project)::isIgnored,
            nowMillis = System.currentTimeMillis(),
            advisoryCount = com.tampwell.staleguard.services.VulnerabilityService.getInstance().advisoryCounter(),
        )

        if (plan.candidates.isEmpty()) {
            UpgradeApplier.notify(project, StaleguardBundle.message("batch.nothing"), NotificationType.INFORMATION)
            return
        }

        val dialog = BatchUpdateDialog(project, plan)
        if (!dialog.showAndGet()) return
        val selected = dialog.selectedCandidates()
        if (selected.isEmpty()) return

        val applied = UpgradeApplier.applyCandidates(project, selected)
        UpgradeApplier.notify(project, StaleguardBundle.message("batch.applied", applied), NotificationType.INFORMATION)
    }
}
