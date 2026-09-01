package com.tampwell.staleguard.impact

/**
 * The candidates for vulnerability checks BEYOND the declared dependencies:
 * every artifact the resolved tree actually pulls in transitively, each with
 * the path that brings it, because "log4j-core is vulnerable" is half an
 * answer and "and spring-boot-starter-logging drags it in" is the other half.
 *
 * Pure tree work. Depth-one nodes are the declared dependencies — the
 * inspections already cover those where they are declared — so only deeper
 * occurrences are candidates. Evicted occurrences are skipped: the version
 * that lost resolution is not on the classpath, and a CVE claim about a jar
 * you do not run is noise.
 */
object TransitiveVulnScan {

    data class Candidate(
        val groupId: String,
        val artifactId: String,
        val version: String,
        /** The shortest path that brings it in, rendered. */
        val via: String,
    )

    fun candidates(roots: List<ProvenanceTrace.Node>): List<Candidate> {
        val best = LinkedHashMap<String, Pair<Int, Candidate>>()
        fun walk(node: ProvenanceTrace.Node, trail: List<String>, depth: Int) {
            if (!node.winner) return
            val path = trail + "${node.artifactId}:${node.version}"
            if (depth >= 2) {
                val key = "${node.groupId}:${node.artifactId}:${node.version}"
                val existing = best[key]
                if (existing == null || existing.first > path.size) {
                    best[key] = path.size to
                        Candidate(node.groupId, node.artifactId, node.version, path.joinToString(" -> "))
                }
            }
            node.children.forEach { walk(it, path, depth + 1) }
        }
        roots.forEach { walk(it, emptyList(), 1) }
        return best.values.map { it.second }
    }
}
