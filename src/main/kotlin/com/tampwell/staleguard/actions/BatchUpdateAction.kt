package com.tampwell.staleguard.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.xml.XmlTag
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.inspection.FixTarget
import com.tampwell.staleguard.maven.PomDependencyCollector
import com.tampwell.staleguard.plan.PlannerInput
import com.tampwell.staleguard.plan.UpgradeCandidate
import com.tampwell.staleguard.plan.UpgradePlanner
import com.tampwell.staleguard.services.VersionLookupService
import com.tampwell.staleguard.settings.StaleguardSettings
import java.util.concurrent.TimeUnit
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * Tools → "Staleguard: Update Dependencies…" — plans every available upgrade
 * from the warm cache, previews them in [BatchUpdateDialog], and applies the
 * selection in one undoable write command.
 *
 * Reads ONLY the warm cache (same invariant as the inspection): dependencies
 * that haven't been resolved yet simply don't appear; opening the pom files
 * first (or the future stats window refresh) warms things up.
 */
class BatchUpdateAction : AnAction() {

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
            inputs = collectInputs(project),
            suggestPrereleases = settings.state.suggestPrereleases,
            abandonmentThresholdMillis = TimeUnit.DAYS.toMillis(365L * settings.state.abandonmentYears),
            ignored = settings::isIgnored,
            nowMillis = System.currentTimeMillis(),
        )

        if (plan.candidates.isEmpty()) {
            notify(project, StaleguardBundle.message("batch.nothing"), NotificationType.INFORMATION)
            return
        }

        val dialog = BatchUpdateDialog(project, plan)
        if (!dialog.showAndGet()) return
        val selected = dialog.selectedCandidates()
        if (selected.isEmpty()) return

        val applied = applyCandidates(project, selected)
        notify(project, StaleguardBundle.message("batch.applied", applied), NotificationType.INFORMATION)
    }

    private fun collectInputs(project: Project): List<PlannerInput> {
        val lookup = VersionLookupService.getInstance()
        val inputs = mutableListOf<PlannerInput>()
        for (mavenProject in MavenProjectsManager.getInstance(project).projects) {
            val model = MavenDomUtil.getMavenDomProjectModel(project, mavenProject.file) ?: continue
            for ((_, declared) in PomDependencyCollector.collectWithDom(model)) {
                val groupId = declared.groupId ?: continue
                val artifactId = declared.artifactId ?: continue
                val snapshot = lookup.peek(com.tampwell.staleguard.repository.Coordinates(groupId, artifactId))
                inputs.add(
                    PlannerInput(
                        moduleName = mavenProject.displayName,
                        declared = declared,
                        known = snapshot?.value,
                        moduleId = mavenProject.file.path,
                    ),
                )
            }
        }
        return inputs
    }

    /** All edits in one write command = one undo step for the whole batch. */
    private fun applyCandidates(project: Project, selected: List<UpgradeCandidate>): Int {
        var applied = 0
        WriteCommandAction.runWriteCommandAction(project, StaleguardBundle.message("batch.command"), null, {
            // Property-controlled versions: one edit per property. If several
            // selected candidates share a property, the highest suggestion wins
            // (a property has exactly one value).
            val byProperty = selected.mapNotNull { c -> c.propertyName?.let { it to c } }
                .groupBy({ it.first }, { it.second })
            for ((property, group) in byProperty) {
                val newVersion = group.maxOf { it.suggestedVersion }.value
                val tag = findPropertyTag(project, property) ?: continue
                tag.value.text = newVersion
                applied += group.size
            }

            // Literal versions: edit each dependency's own <version> tag.
            val literals = selected.filter { it.target == FixTarget.Literal }
            if (literals.isNotEmpty()) {
                for (mavenProject in MavenProjectsManager.getInstance(project).projects) {
                    val wanted = literals.filter { it.moduleId == mavenProject.file.path }
                    if (wanted.isEmpty()) continue
                    val model = MavenDomUtil.getMavenDomProjectModel(project, mavenProject.file) ?: continue
                    for ((dom, declared) in PomDependencyCollector.collectWithDom(model)) {
                        val match = wanted.firstOrNull {
                            it.coordinates.groupId == declared.groupId &&
                                it.coordinates.artifactId == declared.artifactId &&
                                it.currentVersion.value == declared.rawVersion
                        } ?: continue
                        dom.version.stringValue = match.suggestedVersion.value
                        applied++
                    }
                }
            }
        })
        return applied
    }

    private fun findPropertyTag(project: Project, propertyName: String): XmlTag? {
        for (mavenProject in MavenProjectsManager.getInstance(project).projects) {
            val model = MavenDomUtil.getMavenDomProjectModel(project, mavenProject.file) ?: continue
            val tag = model.properties.xmlTag?.subTags?.firstOrNull { it.name == propertyName }
            if (tag != null) return tag
        }
        return null
    }

    private fun notify(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Staleguard")
            .createNotification(StaleguardBundle.message("notification.title"), content, type)
            .notify(project)
    }
}
