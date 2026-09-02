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
            mavenProject.dependencyTree.map { toNode(it, depth = 0) }
        }
    }

    private fun toNode(node: MavenArtifactNode, depth: Int): ProvenanceTrace.Node = ProvenanceTrace.Node(
        groupId = node.artifact.groupId,
        artifactId = node.artifact.artifactId,
        version = node.artifact.version,
        // The depth cap is cycle insurance at the conversion boundary, so a
        // CYCLE-shaped tree can never build an infinite Node graph.
        children = if (depth >= MAX_DEPTH) emptyList() else node.dependencies.map { toNode(it, depth + 1) },
        premanagedVersion = node.premanagedVersion,
        // ADDED is the copy resolution kept; CONFLICT and DUPLICATE mark the
        // occurrences another version or another path beat.
        winner = node.state == MavenArtifactState.ADDED,
    )

    private const val MAX_DEPTH = 64
}
