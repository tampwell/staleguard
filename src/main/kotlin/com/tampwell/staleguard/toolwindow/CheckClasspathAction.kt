package com.tampwell.staleguard.toolwindow

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.impact.ClasspathLinkageService
import com.tampwell.staleguard.impact.LinkageDialog

/**
 * Runs the classpath linkage audit: every jar's calls resolved against what
 * the rest of the classpath actually declares, predicting the
 * NoSuchMethodError a version conflict produces before anything runs.
 * Explicit-intent only, like every check that reads the whole classpath.
 */
internal class CheckClasspathAction : AnAction(
    StaleguardBundle.message("toolwindow.linkage"),
    null,
    com.intellij.icons.AllIcons.Actions.GroupByModuleGroup,
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // The SDK member resolution rides the index; starting mid-indexing
        // would just throw.
        DumbService.getInstance(project).runWhenSmart { run(project) }
    }

    private fun run(project: Project) {
        object : Task.Backgroundable(project, StaleguardBundle.message("linkage.progress"), true) {
            private var result: ClasspathLinkageService.Result? = null

            override fun run(indicator: ProgressIndicator) {
                result = ClasspathLinkageService.getInstance(project).audit(indicator)
            }

            override fun onSuccess() {
                result?.let { LinkageDialog(project, it.report, it.ownCode, it.suggestions).show() }
            }
        }.queue()
    }
}
