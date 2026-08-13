package com.tampwell.staleguard.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.plan.UpgradePlanner
import com.tampwell.staleguard.settings.StaleguardSettings
import com.tampwell.staleguard.version.UpgradeSeverity
import java.util.concurrent.TimeUnit
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * The safest workflow, one click: apply every PATCH upgrade across all Maven
 * modules with no dialog. Patch bumps are the "just do it" tier; anything
 * riskier goes through the batch dialog's preview.
 */
class ApplyAllPatchAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible =
            project != null && MavenProjectsManager.getInstance(project).projects.isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = StaleguardSettings.getInstance()

        val plan = UpgradePlanner.plan(
            inputs = UpgradeApplier.collectInputs(project),
            suggestPrereleases = settings.state.suggestPrereleases,
            abandonmentThresholdMillis = TimeUnit.DAYS.toMillis(365L * settings.state.abandonmentYears),
            ignored = settings::isIgnored,
            nowMillis = System.currentTimeMillis(),
        )

        val patches = plan.candidates.filter { it.severity == UpgradeSeverity.PATCH }
        if (patches.isEmpty()) {
            UpgradeApplier.notify(project, StaleguardBundle.message("patchall.nothing"), NotificationType.INFORMATION)
            return
        }

        val applied = UpgradeApplier.applyCandidates(project, patches)
        UpgradeApplier.notify(project, StaleguardBundle.message("patchall.applied", applied), NotificationType.INFORMATION)
    }
}
