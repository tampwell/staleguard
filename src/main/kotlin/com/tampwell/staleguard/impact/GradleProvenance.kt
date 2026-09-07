package com.tampwell.staleguard.impact

import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.project.dependencies.ArtifactDependencyNode
import com.intellij.openapi.externalSystem.model.project.dependencies.DependencyNode
import com.intellij.openapi.externalSystem.model.project.dependencies.ProjectDependencyNode
import com.intellij.openapi.externalSystem.model.project.dependencies.ReferenceNode
import com.intellij.openapi.externalSystem.model.project.dependencies.ResolutionState
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.project.Project

/**
 * The Gradle half of provenance: the RESOLVED dependency graph the Gradle
 * sync attaches to the external project data (the same graph that powers the
 * IDE's own Dependency Analyzer), converted to the platform-free shape
 * [ProvenanceTrace] walks. Runtime graphs only — what actually ships.
 *
 * When the sync did not collect the graph, this returns nothing and every
 * downstream surface stays silent, which is the standing rule: no line beats
 * a guessed line.
 */
object GradleProvenance {

    fun nodesFor(project: Project): List<ProvenanceTrace.Node> = inReadAction {
        ProjectDataManager.getInstance().getExternalProjectsData(project, GRADLE).flatMap { info ->
            val structure = info.externalProjectStructure ?: return@flatMap emptyList()
            ExternalSystemApiUtil.findAllRecursively(structure, ProjectKeys.DEPENDENCIES_GRAPH).flatMap { data ->
                data.data.componentsDependencies.flatMap { component ->
                    convert(component.runtimeDependenciesGraph.dependencies)
                }
            }
        }
    }

    /**
     * Pure conversion over the platform's serializable node interfaces, so it
     * is testable with fakes. Reference nodes (the graph's de-duplication
     * back-pointers) are skipped: the first full occurrence carries the
     * subtree. Module dependencies become version-less hops whose children
     * are still walked, because a library arriving through a sibling module
     * still arrived.
     */
    fun convert(declared: List<DependencyNode>, depth: Int = 0): List<ProvenanceTrace.Node> {
        if (depth >= MAX_DEPTH) return emptyList()
        return declared.mapNotNull { node ->
            when (node) {
                is ReferenceNode -> null
                is ArtifactDependencyNode -> ProvenanceTrace.Node(
                    groupId = node.group,
                    artifactId = node.module,
                    version = node.version,
                    children = convert(node.dependencies, depth + 1),
                    // UNRESOLVED is the only state that proves a loser; a null
                    // state must not silently discard the whole subtree.
                    winner = node.resolutionState != ResolutionState.UNRESOLVED,
                    note = node.selectionReason?.takeIf { it.isNotBlank() && it != REQUESTED },
                )
                is ProjectDependencyNode -> ProvenanceTrace.Node(
                    groupId = "",
                    artifactId = node.projectName,
                    version = "",
                    children = convert(node.dependencies, depth + 1),
                )
                else -> null
            }
        }
    }

    private val GRADLE = ProjectSystemId("GRADLE")

    /** Gradle's selection reason for a plain, unremarkable dependency. */
    private const val REQUESTED = "requested"

    private const val MAX_DEPTH = 64
}
