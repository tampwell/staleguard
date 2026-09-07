package com.tampwell.staleguard.impact

import com.intellij.openapi.externalSystem.model.project.dependencies.ArtifactDependencyNode
import com.intellij.openapi.externalSystem.model.project.dependencies.DependencyNode
import com.intellij.openapi.externalSystem.model.project.dependencies.ProjectDependencyNode
import com.intellij.openapi.externalSystem.model.project.dependencies.ReferenceNode
import com.intellij.openapi.externalSystem.model.project.dependencies.ResolutionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradleProvenanceTest {

    private fun artifact(
        group: String = "g",
        module: String,
        version: String = "1.0",
        state: ResolutionState? = ResolutionState.RESOLVED,
        reason: String? = null,
        children: List<DependencyNode> = emptyList(),
    ): ArtifactDependencyNode = object : ArtifactDependencyNode {
        override fun getId() = 0L
        override fun getDisplayName() = "$group:$module:$version"
        override fun getResolutionState() = state
        override fun getSelectionReason() = reason
        override fun getDependencies() = children
        override fun getGroup() = group
        override fun getModule() = module
        override fun getVersion() = version
    }

    private fun moduleDep(name: String, children: List<DependencyNode>): ProjectDependencyNode =
        object : ProjectDependencyNode {
            override fun getId() = 0L
            override fun getDisplayName() = name
            override fun getResolutionState() = ResolutionState.RESOLVED
            override fun getSelectionReason() = null
            override fun getDependencies() = children
            override fun getProjectName() = name
            override fun getProjectPath() = ":$name"
        }

    @Test
    fun `artifacts convert with coordinates, children and the selection reason as the note`() {
        val nodes = GradleProvenance.convert(
            listOf(artifact(module = "parent", children = listOf(artifact(module = "child", reason = "between versions 1.0 and 2.0")))),
        )

        val parent = nodes.single()
        assertEquals("parent", parent.artifactId)
        assertNull(parent.note)
        assertEquals("between versions 1.0 and 2.0", parent.children.single().note)
    }

    @Test
    fun `the boring requested reason never becomes a note`() {
        val nodes = GradleProvenance.convert(listOf(artifact(module = "a", reason = "requested")))

        assertNull(nodes.single().note)
    }

    @Test
    fun `reference nodes are skipped, the first occurrence carries the subtree`() {
        val nodes = GradleProvenance.convert(
            listOf(artifact(module = "a", children = listOf(ReferenceNode(7)))),
        )

        assertTrue(nodes.single().children.isEmpty())
    }

    @Test
    fun `module dependencies become versionless hops whose children are walked`() {
        val nodes = GradleProvenance.convert(
            listOf(moduleDep("core", children = listOf(artifact(module = "lib")))),
        )

        val hop = nodes.single()
        assertEquals("", hop.groupId)
        assertEquals("core", hop.artifactId)
        assertEquals("lib", hop.children.single().artifactId)
    }

    @Test
    fun `unresolved is a loser, an absent state is not`() {
        val nodes = GradleProvenance.convert(
            listOf(
                artifact(module = "broken", state = ResolutionState.UNRESOLVED),
                artifact(module = "unknown", state = null),
            ),
        )

        assertEquals(mapOf("broken" to false, "unknown" to true), nodes.associate { it.artifactId to it.winner })
    }
}
