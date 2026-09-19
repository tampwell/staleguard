package com.tampwell.staleguard.reach

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.actions.UpgradeApplier

/**
 * Checks, for every vulnerable artifact on the project's classpaths, whether
 * the project's own code reaches what the fix changed. Explicit-intent only:
 * it may download fixed releases to compare against.
 */
class CheckReachabilityAction : AnAction(
    StaleguardBundle.message("reach.action"),
    StaleguardBundle.message("reach.action.description"),
    AllIcons.Actions.Find,
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        object : Task.Backgroundable(project, StaleguardBundle.message("reach.progress"), true) {
            private var summary: ReachabilityService.Summary? = null

            override fun run(indicator: ProgressIndicator) {
                summary = ReachabilityService.getInstance(project).check(indicator)
            }

            override fun onSuccess() {
                summary?.let { report(project, it) }
            }
        }.queue()
    }

    private fun report(project: Project, summary: ReachabilityService.Summary) {
        val text = summaryText(summary)
        val type = if (summary.reached > 0) NotificationType.WARNING else NotificationType.INFORMATION
        UpgradeApplier.notify(project, text, type)
    }

    internal companion object {
        fun summaryText(summary: ReachabilityService.Summary): String {
            val main = when {
                summary.notBuilt -> StaleguardBundle.message("reach.summary.notbuilt")
                summary.vulnerable == 0 -> StaleguardBundle.message("reach.summary.none")
                else -> StaleguardBundle.message(
                    "reach.summary",
                    summary.vulnerable, summary.reached, summary.reachedInTestsOnly, summary.notReached, summary.unknown,
                )
            }
            return if (summary.notYetLookedUp > 0) {
                main + StaleguardBundle.message("reach.summary.cold", summary.notYetLookedUp)
            } else {
                main
            }
        }
    }
}
