package com.tampwell.staleguard.impact

import com.intellij.openapi.project.Project
import org.jetbrains.idea.maven.model.MavenArtifactNode
import org.jetbrains.idea.maven.model.MavenArtifactState
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * The Maven half of provenance: the IDE's already-resolved dependency tree
 * converted to the platform-free shape [ProvenanceTrace] walks. Maven only,
 * honestly — Gradle exposes no resolved tree inside the IDE, and a guessed
 * path is worse than the pointer to `gradle dependencies`.
 */
object MavenProvenance {

    fun nodesFor(project: Project): List<ProvenanceTrace.Node> = inReadAction {
        MavenProjectsManager.getInstance(project).projects.flatMap { mavenProject ->
            mavenProject.dependencyTree.map(::toNode)
        }
    }

    private fun toNode(node: MavenArtifactNode): ProvenanceTrace.Node = ProvenanceTrace.Node(
        groupId = node.artifact.groupId,
        artifactId = node.artifact.artifactId,
        version = node.artifact.version,
        children = node.dependencies.map(::toNode),
        premanagedVersion = node.premanagedVersion,
        // ADDED is the copy resolution kept; CONFLICT and DUPLICATE mark the
        // occurrences another version or another path beat.
        winner = node.state == MavenArtifactState.ADDED,
    )
}
