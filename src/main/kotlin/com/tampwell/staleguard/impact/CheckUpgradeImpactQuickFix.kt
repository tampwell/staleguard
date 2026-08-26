package com.tampwell.staleguard.impact

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Iconable
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.repository.Coordinates
import javax.swing.Icon

/**
 * "Will this upgrade break my code?" — compares the two versions' binaries and
 * reports the removed members this project actually calls, with call sites.
 *
 * Downloads a jar, so like [com.tampwell.staleguard.inspection.ShowChangelogQuickFix]
 * it is explicit-intent only and never runs during highlighting.
 */
class CheckUpgradeImpactQuickFix(
    private val coordinate: String,
    private val currentVersion: String,
    private val suggestedVersion: String,
) : LocalQuickFix, Iconable {

    override fun getIcon(flags: Int): Icon = AllIcons.Actions.DependencyAnalyzer

    override fun startInWriteAction(): Boolean = false

    override fun getFamilyName(): String = StaleguardBundle.message("fix.impact.family")

    override fun getName(): String =
        StaleguardBundle.message("fix.impact.name", currentVersion, suggestedVersion)

    // Downloads and opens a dialog — never during preview.
    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val parts = coordinate.split(':', limit = 2)
        if (parts.size != 2) return
        val coordinates = Coordinates(parts[0], parts[1])

        // The whole analysis is index-backed; starting it mid-indexing would
        // just throw. Saying so beats a stack trace in the log.
        if (DumbService.isDumb(project)) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Staleguard")
                .createNotification(
                    StaleguardBundle.message("notification.title"),
                    StaleguardBundle.message("impact.indexing"),
                    NotificationType.INFORMATION,
                )
                .notify(project)
            return
        }

        object : Task.Backgroundable(
            project,
            StaleguardBundle.message("impact.progress", coordinate, currentVersion, suggestedVersion),
            true,
        ) {
            private var report: ImpactReport? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                report = UpgradeImpactService.getInstance(project)
                    .analyze(coordinates, currentVersion, suggestedVersion, indicator)
            }

            override fun onSuccess() {
                report?.let { ImpactDialog(project, it).show() }
            }
        }.queue()
    }
}
