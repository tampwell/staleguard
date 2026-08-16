package com.tampwell.staleguard.toolwindow

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.tampwell.staleguard.maven.PomDependencyCollector
import com.tampwell.staleguard.model.DeclaredDependency
import com.tampwell.staleguard.plan.PlannerInput
import com.tampwell.staleguard.gradle.GradleTextScanner
import com.tampwell.staleguard.gradle.KtsDependencyCollector
import com.tampwell.staleguard.gradle.VersionCatalog
import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.services.VersionLookupService
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * One collection pass shared by the statistics and timeline panels: Maven
 * modules via the DOM (PSI-anchored, as the inspections use), plus Gradle
 * build files via [GradleTextScanner] — text-based on purpose, so the tool
 * window works even where the Groovy/Kotlin plugins are disabled.
 */
internal object BuildFileRows {

    class Entry(val input: PlannerInput, val file: VirtualFile, val offset: Int)

    fun collect(project: Project): List<Entry> {
        val lookup = VersionLookupService.getInstance()
        val entries = mutableListOf<Entry>()

        // Maven modules (existing behavior)
        for (mavenProject in MavenProjectsManager.getInstance(project).projects) {
            val model = MavenDomUtil.getMavenDomProjectModel(project, mavenProject.file) ?: continue
            for ((dom, declared) in PomDependencyCollector.collectWithDom(model)) {
                val known = declared.groupId?.let { g ->
                    declared.artifactId?.let { a -> lookup.peek(Coordinates(g, a))?.value }
                }
                entries += Entry(
                    input = PlannerInput(
                        moduleName = mavenProject.displayName,
                        declared = declared,
                        known = known,
                        moduleId = mavenProject.file.path,
                    ),
                    file = mavenProject.file,
                    offset = dom.xmlTag?.textOffset ?: 0,
                )
            }
        }

        // Gradle build files (text scan; index lookups need smart mode —
        // during indexing this pass simply yields Maven rows, and the next
        // freshness event rebuilds with everything)
        if (!DumbService.isDumb(project)) {
            val scope = GlobalSearchScope.projectScope(project)
            for (fileName in listOf("build.gradle", "build.gradle.kts")) {
                for (buildFile in FilenameIndex.getVirtualFilesByName(fileName, scope)) {
                    val text = runCatching { String(buildFile.contentsToByteArray()) }.getOrNull() ?: continue
                    val catalog = KtsDependencyCollector.findCatalogFile(buildFile)
                        ?.let { runCatching { VersionCatalog.parse(String(it.contentsToByteArray())) }.getOrNull() }
                        ?: VersionCatalog.EMPTY
                    val moduleName = buildFile.parent?.name ?: buildFile.name
                    for (dep in GradleTextScanner.scan(text, catalog)) {
                        entries += Entry(
                            input = PlannerInput(
                                moduleName = moduleName,
                                declared = DeclaredDependency(
                                    groupId = dep.group,
                                    artifactId = dep.name,
                                    rawVersion = dep.version,
                                    resolvedVersion = dep.version,
                                    origin = DeclaredDependency.Origin.DEPENDENCIES,
                                ),
                                known = lookup.peek(Coordinates(dep.group, dep.name))?.value,
                                moduleId = buildFile.path,
                            ),
                            file = buildFile,
                            offset = dep.offset,
                        )
                    }
                }
            }
        }

        return entries
    }
}
