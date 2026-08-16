package com.tampwell.staleguard.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.xml.XmlTag
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.gradle.GradleTextScanner
import com.tampwell.staleguard.gradle.KtsDependencyCollector
import com.tampwell.staleguard.gradle.VersionCatalog
import com.tampwell.staleguard.inspection.FixTarget
import com.tampwell.staleguard.maven.PomDependencyCollector
import com.tampwell.staleguard.plan.PlannerInput
import com.tampwell.staleguard.plan.UpgradeCandidate
import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.services.VersionLookupService
import com.tampwell.staleguard.toolwindow.BuildFileRows
import com.tampwell.staleguard.version.MavenVersion
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
        // Gradle rows share the tool window's collector; their moduleId is the
        // build file path, which is how applyCandidates tells them apart.
        inputs += BuildFileRows.collect(project).map { it.input }
        return inputs
    }

    private fun isGradlePath(path: String) = path.endsWith(".gradle") || path.endsWith(".gradle.kts")

    /** All edits in one write command = one undo step for the whole batch. */
    fun applyCandidates(project: Project, selected: List<UpgradeCandidate>): Int {
        var applied = 0
        WriteCommandAction.runWriteCommandAction(project, StaleguardBundle.message("batch.command"), null, {
            applied += applyGradleCandidates(selected.filter { isGradlePath(it.moduleId) })
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

    /**
     * Gradle edits re-scan each build file at apply time — the dialog may have
     * been open a while, so collected offsets are treated as hints, never
     * trusted. Catalog-referenced versions dedupe to one `[versions]` edit per
     * key (highest suggestion wins, same rule as Maven properties); string
     * notations are replaced bottom-up so earlier edits can't shift later
     * offsets.
     */
    private fun applyGradleCandidates(candidates: List<UpgradeCandidate>): Int {
        var applied = 0
        for ((path, wanted) in candidates.groupBy { it.moduleId }) {
            val buildFile = LocalFileSystem.getInstance().findFileByPath(path) ?: continue
            val document = FileDocumentManager.getInstance().getDocument(buildFile) ?: continue

            val catalogFile = KtsDependencyCollector.findCatalogFile(buildFile)
            val catalogDocument = catalogFile?.let { FileDocumentManager.getInstance().getDocument(it) }
            val catalog = catalogDocument?.let { VersionCatalog.parse(it.text) } ?: VersionCatalog.EMPTY

            val scanned = GradleTextScanner.scan(document.text, catalog)

            val catalogEdits = mutableMapOf<String, String>() // versionKey -> new version
            val notationEdits = mutableListOf<Triple<Int, Int, String>>() // start, end, replacement

            for (candidate in wanted) {
                val hit = scanned.firstOrNull {
                    it.group == candidate.coordinates.groupId &&
                        it.name == candidate.coordinates.artifactId &&
                        it.version == candidate.currentVersion.value
                } ?: continue

                val accessor = hit.catalogAccessor
                if (accessor != null) {
                    val versionKey = catalog.resolve(accessor)?.versionKey ?: continue
                    val newVersion = candidate.suggestedVersion.value
                    catalogEdits.merge(versionKey, newVersion) { a, b ->
                        maxOf(MavenVersion(a), MavenVersion(b)).value
                    }
                    applied++
                } else {
                    val notation = "${hit.group}:${hit.name}:${hit.version}"
                    val start = hit.offset + 1 // past the opening quote
                    if (document.text.regionMatches(start, notation, 0, notation.length)) {
                        notationEdits += Triple(start, start + notation.length, "${hit.group}:${hit.name}:${candidate.suggestedVersion.value}")
                        applied++
                    }
                }
            }

            notationEdits.sortedByDescending { it.first }
                .forEach { (start, end, replacement) -> document.replaceString(start, end, replacement) }

            if (catalogDocument != null) {
                for ((versionKey, newVersion) in catalogEdits) {
                    val range = VersionCatalog.versionValueRange(catalogDocument.text, versionKey) ?: continue
                    catalogDocument.replaceString(range.first, range.last + 1, newVersion)
                }
            }
        }
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
