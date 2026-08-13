package com.tampwell.staleguard.maven

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.tampwell.staleguard.model.DeclaredDependency
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.model.MavenDomDependency
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel

/**
 * Reads the *declared* dependencies out of a pom.xml via IntelliJ's Maven DOM
 * layer. DOM (not raw XML) so that later milestones can navigate from a
 * dependency back to its exact PSI element for in-editor highlighting and
 * write-back.
 *
 * Must be called under a read action (action event handlers and inspections
 * already provide one).
 */
object PomDependencyCollector {

    fun collect(project: Project, pomFile: VirtualFile): List<DeclaredDependency> {
        val model = MavenDomUtil.getMavenDomProjectModel(project, pomFile) ?: return emptyList()
        val properties = effectiveProperties(model)

        val direct = model.dependencies.dependencies
            .map { it.toDeclared(properties, DeclaredDependency.Origin.DEPENDENCIES) }
        val managed = model.dependencyManagement.dependencies.dependencies
            .map { it.toDeclared(properties, DeclaredDependency.Origin.DEPENDENCY_MANAGEMENT) }

        return direct + managed
    }

    /**
     * The `<properties>` block plus the `project.*` built-ins that version
     * declarations most commonly reference. Parent-inherited properties come
     * in a later milestone.
     */
    private fun effectiveProperties(model: MavenDomProjectModel): Map<String, String> {
        val result = mutableMapOf<String, String>()

        model.properties.xmlTag?.subTags?.forEach { tag ->
            tag.value.trimmedText.takeIf { it.isNotEmpty() }?.let { result[tag.name] = it }
        }

        val version = model.version.stringValue ?: model.mavenParent.version.stringValue
        val groupId = model.groupId.stringValue ?: model.mavenParent.groupId.stringValue
        version?.let { result["project.version"] = it }
        groupId?.let { result["project.groupId"] = it }
        model.artifactId.stringValue?.let { result["project.artifactId"] = it }

        return result
    }

    private fun MavenDomDependency.toDeclared(
        properties: Map<String, String>,
        origin: DeclaredDependency.Origin,
    ): DeclaredDependency {
        val raw = version.stringValue
        return DeclaredDependency(
            groupId = groupId.stringValue,
            artifactId = artifactId.stringValue,
            rawVersion = raw,
            resolvedVersion = raw?.let { MavenPropertyInterpolator.interpolate(it, properties) },
            origin = origin,
        )
    }
}
