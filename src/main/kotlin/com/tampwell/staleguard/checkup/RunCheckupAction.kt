package com.tampwell.staleguard.checkup

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.tampwell.staleguard.StaleguardBundle

/** Runs the whole checkup and shows the report card. Also the first-run toast's one button. */
class RunCheckupAction : AnAction(
    StaleguardBundle.message("checkup.action"),
    StaleguardBundle.message("checkup.action.description"),
    AllIcons.Actions.Checked,
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        run(e.project ?: return)
    }

    companion object {
        private const val LAST_SCORE = "staleguard.checkup.lastScore"

        fun run(project: Project) {
            object : Task.Backgroundable(project, StaleguardBundle.message("checkup.progress"), true) {
                private var outcome: CheckupService.Outcome? = null

                override fun run(indicator: ProgressIndicator) {
                    outcome = CheckupService.getInstance(project).run(indicator)
                }

                override fun onSuccess() {
                    val result = outcome ?: return
                    val properties = PropertiesComponent.getInstance(project)
                    val previous = properties.getValue(LAST_SCORE)?.toIntOrNull()
                    properties.setValue(LAST_SCORE, result.report.score.toString())
                    CheckupDialog(project, result, previous).show()
                    if (result.report.findings.isNotEmpty()) {
                        com.tampwell.staleguard.onboarding.FeedbackPrompt.valueDelivered(project)
                    }
                }
            }.queue()
        }
    }
}
