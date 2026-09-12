package com.tampwell.staleguard.report

import com.tampwell.staleguard.gradle.Lockfile
import com.tampwell.staleguard.impact.ProvenanceTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SbomGraphTest {

    private fun node(
        coordinate: String,
        children: List<ProvenanceTrace.Node> = emptyList(),
        winner: Boolean = true,
    ): ProvenanceTrace.Node {
        val (group, artifact, version) = coordinate.split(':')
        return ProvenanceTrace.Node(group, artifact, version, children, winner = winner)
    }

    @Test
    fun `transitives join the bill with their edges`() {
        val roots = listOf(
            node("g:direct:1.0", children = listOf(node("g:transitive:2.0"))),
        )

        val result = SbomGraph.collect(roots)

        assertEquals(listOf("g:direct:1.0", "g:transitive:2.0"), result.artifacts.map { it.key })
        assertEquals(listOf("g:direct:1.0"), result.rootDependsOn)
        assertEquals(mapOf("g:direct:1.0" to listOf("g:transitive:2.0")), result.dependsOn)
    }

    @Test
    fun `an evicted occurrence never ships`() {
        val roots = listOf(
            node("g:a:1.0", winner = false, children = listOf(node("g:hidden:9.9"))),
            node("g:a:2.0"),
        )

        val result = SbomGraph.collect(roots)

        assertEquals(listOf("g:a:2.0"), result.artifacts.map { it.key })
        assertTrue(result.artifacts.none { it.artifactId == "hidden" })
    }

    @Test
    fun `a module hop passes its children through to the parent`() {
        val roots = listOf(
            node("g:app:1.0", children = listOf(
                ProvenanceTrace.Node("", "sibling-module", "", listOf(node("g:lib:3.0"))),
            )),
        )

        val result = SbomGraph.collect(roots)

        assertEquals(listOf("g:app:1.0", "g:lib:3.0"), result.artifacts.map { it.key })
        assertEquals(mapOf("g:app:1.0" to listOf("g:lib:3.0")), result.dependsOn)
    }

    @Test
    fun `a root level module hop makes its children direct`() {
        val roots = listOf(
            ProvenanceTrace.Node("", "sibling", "", listOf(node("g:lib:3.0"))),
        )

        val result = SbomGraph.collect(roots)

        assertEquals(listOf("g:lib:3.0"), result.rootDependsOn)
    }

    @Test
    fun `the lock overrides the graph version and keeps the graph answer as a property`() {
        val roots = listOf(node("g:a:1.1", children = listOf(node("g:b:2.0"))))
        val locked = listOf(Lockfile.Locked("g", "a", "1.0", listOf("compileClasspath")))

        val result = SbomGraph.collect(roots, locked)

        val a = result.artifacts.single { it.artifactId == "a" }
        assertEquals("1.0", a.version)
        assertEquals("1.1", a.graphVersion)
        // Edges follow the exported (locked) key.
        assertEquals(mapOf("g:a:1.0" to listOf("g:b:2.0")), result.dependsOn)
        val b = result.artifacts.single { it.artifactId == "b" }
        assertNull(b.graphVersion)
    }

    @Test
    fun `a lock that agrees with the graph adds nothing`() {
        val roots = listOf(node("g:a:1.0"))
        val locked = listOf(
            Lockfile.Locked("g", "a", "1.0", listOf("compileClasspath")),
            Lockfile.Locked("g", "not-in-graph", "5.0", listOf("compileClasspath")),
        )

        val result = SbomGraph.collect(roots, locked)

        assertEquals(listOf("g:a:1.0"), result.artifacts.map { it.key })
        assertNull(result.artifacts.single().graphVersion)
    }

    @Test
    fun `repeated occurrences dedupe to one component and one edge set`() {
        val shared = node("g:shared:1.0", children = listOf(node("g:leaf:0.1")))
        val roots = listOf(
            node("g:a:1.0", children = listOf(shared)),
            node("g:b:1.0", children = listOf(shared)),
        )

        val result = SbomGraph.collect(roots)

        assertEquals(1, result.artifacts.count { it.artifactId == "shared" })
        assertEquals(listOf("g:leaf:0.1"), result.dependsOn["g:shared:1.0"])
        assertEquals(listOf("g:shared:1.0"), result.dependsOn["g:a:1.0"])
        assertEquals(listOf("g:shared:1.0"), result.dependsOn["g:b:1.0"])
    }

    @Test
    fun `a cyclic graph terminates`() {
        val children = mutableListOf<ProvenanceTrace.Node>()
        val cyclic = ProvenanceTrace.Node("g", "cycle", "1.0", children)
        children += cyclic

        val result = SbomGraph.collect(listOf(cyclic))

        assertEquals(listOf("g:cycle:1.0"), result.artifacts.map { it.key })
        assertTrue(result.dependsOn["g:cycle:1.0"].isNullOrEmpty())
    }
}
