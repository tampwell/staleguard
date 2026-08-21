package com.tampwell.staleguard.maven

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.tampwell.staleguard.model.DeclaredDependency
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.model.MavenDomArtifactCoordinates
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
/** A declared dependency paired with its DOM element, for PSI-anchored UI. */
data class DomDeclaredDependency(
    val dom: MavenDomArtifactCoordinates,
    val declared: DeclaredDependency,
)

object PomDependencyCollector {

    fun collect(project: Project, pomFile: VirtualFile): List<DeclaredDependency> {
        val model = MavenDomUtil.getMavenDomProjectModel(project, pomFile) ?: return emptyList()
        return collectWithDom(model).map { it.declared }
    }

    /** Same walk, but keeps the DOM handle so callers can highlight/edit. */
    fun collectWithDom(model: MavenDomProjectModel): List<DomDeclaredDependency> {
        // Imported effective properties fill what this file doesn't declare —
        // parent-inherited versions, profile properties, ${revision}-style CI
        // versions. Local DOM values win: they're fresher than the last import.
        val properties = importedProperties(model) + effectiveProperties(model)

        val direct = model.dependencies.dependencies
            .map { DomDeclaredDependency(it, it.toDeclared(properties, DeclaredDependency.Origin.DEPENDENCIES)) }
        val managed = model.dependencyManagement.dependencies.dependencies
            .map { dep ->
                // scope=import pulls in a whole BOM — same platform role as a
                // parent pom, and it gets the same "one edit" message.
                val origin = if (dep.scope.stringValue == "import") {
                    DeclaredDependency.Origin.BOM_IMPORT
                } else {
                    DeclaredDependency.Origin.DEPENDENCY_MANAGEMENT
                }
                DomDeclaredDependency(dep, dep.toDeclared(properties, origin))
            }

        // The <parent> IS a dependency — for Spring Boot projects it is THE
        // dependency, the platform BOM every managed version flows from.
        // JetBrains' own tooling ignores it (IDEA-286295); we don't.
        val parent = model.mavenParent.takeIf { it.artifactId.stringValue != null }
            ?.let { DomDeclaredDependency(it, it.toDeclared(properties, DeclaredDependency.Origin.PARENT)) }

        return listOfNotNull(parent) + direct + managed
    }

    /**
     * Properties from IntelliJ's imported Maven model — the resolved
     * effective set, parents and profiles included. Empty when the file isn't
     * part of an imported project (unlinked pom, import still running).
     */
    private fun importedProperties(model: MavenDomProjectModel): Map<String, String> = try {
        val xml = model.xmlElement
        val file = xml?.containingFile?.originalFile?.virtualFile
        if (file == null) {
            emptyMap()
        } else {
            org.jetbrains.idea.maven.project.MavenProjectsManager.getInstance(xml.project)
                .findProject(file)
                ?.properties
                ?.entries
                ?.associate { it.key.toString() to it.value.toString() }
                .orEmpty()
        }
    } catch (_: Exception) {
        emptyMap()
    }

    /**
     * The `<properties>` block plus the `project.*` built-ins that version
     * declarations most commonly reference.
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

    private fun MavenDomArtifactCoordinates.toDeclared(
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
