package com.tampwell.staleguard.impact

/**
 * Answers the question every doctor finding raises next: where did this
 * version COME FROM. Walks a build tool's resolved dependency tree and
 * renders the path(s) that brought the artifact in, with the pins that
 * decided the version called out by name.
 */
object ProvenanceTrace {

    /** One resolved node, platform-free. */
    data class Node(
        val groupId: String,
        val artifactId: String,
        val version: String,
        val children: List<Node> = emptyList(),
        /** The declared version dependencyManagement overrode, when it did. */
        val premanagedVersion: String? = null,
        /** False when resolution evicted this occurrence in favor of another. */
        val winner: Boolean = true,
    )

    data class Hop(val label: String)

    data class Path(val hops: List<Hop>, val winner: Boolean) {
        fun render(): String = hops.joinToString(" -> ") { it.label }
    }

    /**
     * Every path from a root to an occurrence of [groupId]:[artifactId],
     * winner paths first, capped at [MAX_PATHS] — two paths explain a
     * conflict; forty restate it.
     */
    fun trace(roots: List<Node>, groupId: String, artifactId: String): List<Path> {
        val found = mutableListOf<Path>()
        fun walk(node: Node, trail: List<Hop>) {
            if (found.size >= HARD_STOP) return
            val hop = Hop(label(node))
            val path = trail + hop
            if (node.groupId == groupId && node.artifactId == artifactId) {
                found += Path(path, node.winner)
                return // the interesting story ends at the artifact itself
            }
            node.children.forEach { walk(it, path) }
        }
        roots.forEach { walk(it, emptyList()) }
        return found.sortedByDescending { it.winner }.take(MAX_PATHS)
    }

    private fun label(node: Node): String {
        val base = "${node.artifactId}:${node.version}"
        return when {
            node.premanagedVersion != null ->
                "$base (pinned from ${node.premanagedVersion} by dependencyManagement)"
            !node.winner -> "$base (evicted)"
            else -> base
        }
    }

    private const val MAX_PATHS = 4
    private const val HARD_STOP = 64
}
