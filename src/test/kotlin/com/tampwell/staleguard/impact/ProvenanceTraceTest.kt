package com.tampwell.staleguard.impact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvenanceTraceTest {

    private fun node(
        artifactId: String,
        version: String,
        vararg children: ProvenanceTrace.Node,
        premanaged: String? = null,
        winner: Boolean = true,
    ) = ProvenanceTrace.Node("com.example", artifactId, version, children.toList(), premanaged, winner)

    @Test
    fun `a transitive artifact renders the full path from the root`() {
        val tree = node("app", "1.0", node("databind", "2.19.0", node("core", "2.13.0")))

        val path = ProvenanceTrace.trace(listOf(tree), "com.example", "core").single()

        assertEquals("app:1.0 -> databind:2.19.0 -> core:2.13.0", path.render())
    }

    @Test
    fun `a dependencyManagement pin is called out on its hop`() {
        val tree = node("app", "1.0", node("core", "2.13.0", premanaged = "2.15.0"))

        val path = ProvenanceTrace.trace(listOf(tree), "com.example", "core").single()

        assertTrue("pinned note missing: ${path.render()}", "pinned from 2.15.0" in path.render())
    }

    @Test
    fun `the winning path sorts before the evicted one`() {
        val viaA = node("a", "1.0", node("core", "2.13.0", winner = true))
        val viaB = node("b", "1.0", node("core", "2.15.0", winner = false))

        val paths = ProvenanceTrace.trace(listOf(viaB, viaA), "com.example", "core")

        assertEquals(2, paths.size)
        assertTrue(paths.first().winner)
        assertTrue("evicted note missing" , "(evicted)" in paths.last().render())
    }

    @Test
    fun `paths are capped so forty duplicates do not restate one conflict`() {
        val roots = (1..40).map { node("root$it", "1.0", node("core", "2.13.0")) }

        assertEquals(4, ProvenanceTrace.trace(roots, "com.example", "core").size)
    }

    @Test
    fun `the walk stops at the artifact, its own children are not the story`() {
        val tree = node("app", "1.0", node("core", "2.13.0", node("deeper", "9.9")))

        val path = ProvenanceTrace.trace(listOf(tree), "com.example", "core").single()

        assertTrue("deeper" !in path.render())
    }

    @Test
    fun `an artifact nowhere in the tree yields no paths, never a guess`() {
        val tree = node("app", "1.0", node("core", "2.13.0"))

        assertTrue(ProvenanceTrace.trace(listOf(tree), "com.example", "absent").isEmpty())
    }

    @org.junit.Test
    fun `a cyclic tree terminates instead of overflowing`() {
        val children = mutableListOf<ProvenanceTrace.Node>()
        val cyclic = ProvenanceTrace.Node("g", "self", "1.0", children)
        children += cyclic

        val paths = ProvenanceTrace.trace(listOf(cyclic), "g", "missing")

        org.junit.Assert.assertTrue(paths.isEmpty()) // walked, capped, returned
    }
}
