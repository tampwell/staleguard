package com.tampwell.staleguard.toolwindow

import com.intellij.openapi.diagnostic.logger
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
import com.tampwell.staleguard.repository.DeclaredRepositories
import com.tampwell.staleguard.repository.ExtraRepositories
import com.tampwell.staleguard.services.VersionLookupService
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.io.IOException

/**
 * One collection pass shared by the statistics and timeline panels: Maven
 * modules via the DOM, Gradle build files via [GradleTextScanner].
 */
internal object BuildFileRows {

    private val LOG = logger<BuildFileRows>()

    class Entry(val input: PlannerInput, val file: VirtualFile, val offset: Int)

    fun collect(project: Project): List<Entry> {
        val lookup = VersionLookupService.getInstance()
        val entries = mutableListOf<Entry>()

        for (mavenProject in MavenProjectsManager.getInstance(project).projects) {
            // Declared remote repositories join the lookup chain as
            // last-resort sources (anonymous read only).
            readText(mavenProject.file)?.let {
                ExtraRepositories.getInstance().register(DeclaredRepositories.fromPomXml(it))
            }
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

        // FilenameIndex needs smart mode; during indexing the panel shows
        // Maven rows only and the next freshness event completes the picture.
        if (!DumbService.isDumb(project)) {
            val scope = GlobalSearchScope.projectScope(project)
            for (fileName in listOf("build.gradle", "build.gradle.kts")) {
                for (buildFile in FilenameIndex.getVirtualFilesByName(fileName, scope)) {
                    val text = readText(buildFile) ?: continue
                    ExtraRepositories.getInstance().register(DeclaredRepositories.fromGradle(text))
                    val catalog = KtsDependencyCollector.findCatalogFile(buildFile)
                        ?.let { file -> readText(file)?.let(::parseCatalog) }
                        ?: VersionCatalog.EMPTY
                    val gradleProperties = com.tampwell.staleguard.gradle.GradleProperties.findFile(buildFile)
                        ?.let { file -> readText(file)?.let(com.tampwell.staleguard.gradle.GradleProperties::parse) }
                        .orEmpty() +
                        runCatching { com.tampwell.staleguard.gradle.BuildSrcVersions.find(buildFile) }.getOrDefault(emptyMap())
                    val moduleName = buildFile.parent?.name ?: buildFile.name
                    for (dep in GradleTextScanner.scan(text, catalog, gradleProperties, includePluginBlocks = true)) {
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

    private fun readText(file: VirtualFile): String? = try {
        String(file.contentsToByteArray())
    } catch (e: IOException) {
        LOG.debug("Skipping unreadable build file ${file.path}", e)
        null
    }

    private fun parseCatalog(text: String): VersionCatalog.Parsed? = try {
        VersionCatalog.parse(text)
    } catch (e: Exception) {
        LOG.debug("Ignoring malformed version catalog", e)
        null
    }
}
