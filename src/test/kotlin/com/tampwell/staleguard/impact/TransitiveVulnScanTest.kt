package com.tampwell.staleguard.impact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitiveVulnScanTest {

    private fun node(
        artifactId: String,
        version: String = "1.0",
        winner: Boolean = true,
        children: List<ProvenanceTrace.Node> = emptyList(),
    ) = ProvenanceTrace.Node("g", artifactId, version, children, winner = winner)

    @Test
    fun `declared dependencies are not candidates, their transitives are`() {
        val roots = listOf(node("declared", children = listOf(node("transitive"))))

        val candidates = TransitiveVulnScan.candidates(roots)

        assertEquals(listOf("transitive"), candidates.map { it.artifactId })
        assertEquals("declared:1.0 -> transitive:1.0", candidates.single().via)
    }

    @Test
    fun `an artifact reached twice keeps its shortest path`() {
        val deep = node("a", children = listOf(node("b", children = listOf(node("target")))))
        val shallow = node("c", children = listOf(node("target")))

        val via = TransitiveVulnScan.candidates(listOf(deep, shallow)).single { it.artifactId == "target" }.via

        assertEquals("c:1.0 -> target:1.0", via)
    }

    @Test
    fun `evicted occurrences are not candidates, they are not on the classpath`() {
        val roots = listOf(node("declared", children = listOf(node("gone", winner = false))))

        assertTrue(TransitiveVulnScan.candidates(roots).isEmpty())
    }

    @Test
    fun `a cyclic tree terminates instead of overflowing`() {
        // Maven marks CYCLE nodes for a reason; children sharing a mutable
        // list makes a genuinely cyclic Node graph.
        val children = mutableListOf<ProvenanceTrace.Node>()
        val cyclic = ProvenanceTrace.Node("g", "self", "1.0", children)
        children += cyclic

        val candidates = TransitiveVulnScan.candidates(listOf(cyclic))

        assertTrue(candidates.isNotEmpty()) // it returned at all, capped by depth
    }

    @Test
    fun `two versions of the same artifact are separate candidates`() {
        val roots = listOf(
            node("a", children = listOf(node("dup", version = "1.0"))),
            node("b", children = listOf(node("dup", version = "2.0"))),
        )

        assertEquals(setOf("1.0", "2.0"), TransitiveVulnScan.candidates(roots).map { it.version }.toSet())
    }
}
