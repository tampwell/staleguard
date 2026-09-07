package com.tampwell.staleguard.impact

import com.intellij.openapi.project.Project

/**
 * The one place that answers "what does this project's resolved dependency
 * tree look like": Maven's tree and Gradle's graph, in the same shape. A
 * build system that exposes nothing contributes nothing, and every consumer
 * inherits that honesty.
 */
object Provenance {

    fun nodesFor(project: Project): List<ProvenanceTrace.Node> =
        MavenProvenance.nodesFor(project) + GradleProvenance.nodesFor(project)
}
