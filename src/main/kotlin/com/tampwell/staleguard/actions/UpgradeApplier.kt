package com.tampwell.staleguard.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.xml.XmlTag
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.inspection.FixTarget
import com.tampwell.staleguard.maven.PomDependencyCollector
import com.tampwell.staleguard.plan.PlannerInput
import com.tampwell.staleguard.plan.UpgradeCandidate
import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.services.VersionLookupService
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * Shared machinery for every "apply upgrades" surface (batch dialog,
 * apply-all-patch): planner input collection from the warm cache, and the
 * single-write-command application of a candidate selection.
 */
object UpgradeApplier {

    fun collectInputs(project: Project): List<PlannerInput> {
        val lookup = VersionLookupService.getInstance()
        val inputs = mutableListOf<PlannerInput>()
        for (mavenProject in MavenProjectsManager.getInstance(project).projects) {
            val model = MavenDomUtil.getMavenDomProjectModel(project, mavenProject.file) ?: continue
            for ((_, declared) in PomDependencyCollector.collectWithDom(model)) {
                val groupId = declared.groupId ?: continue
                val artifactId = declared.artifactId ?: continue
                inputs.add(
                    PlannerInput(
                        moduleName = mavenProject.displayName,
                        declared = declared,
                        known = lookup.peek(Coordinates(groupId, artifactId))?.value,
                        moduleId = mavenProject.file.path,
                    ),
                )
            }
        }
        return inputs
    }

    /** All edits in one write command = one undo step for the whole batch. */
    fun applyCandidates(project: Project, selected: List<UpgradeCandidate>): Int {
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

    fun notify(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Staleguard")
            .createNotification(StaleguardBundle.message("notification.title"), content, type)
            .notify(project)
    }

    private fun findPropertyTag(project: Project, propertyName: String): XmlTag? {
        for (mavenProject in MavenProjectsManager.getInstance(project).projects) {
            val model = MavenDomUtil.getMavenDomProjectModel(project, mavenProject.file) ?: continue
            val tag = model.properties.xmlTag?.subTags?.firstOrNull { it.name == propertyName }
            if (tag != null) return tag
        }
        return null
    }
}
