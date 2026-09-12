package com.tampwell.staleguard.report

import com.tampwell.staleguard.gradle.Lockfile
import com.tampwell.staleguard.impact.ProvenanceTrace

/**
 * Flattens the resolved dependency trees into the full bill of materials:
 * every artifact that actually ships, direct and transitive, plus the
 * parent-child edges CycloneDX renders as the dependency graph. Pure - the
 * platform hands in provenance nodes and lockfile entries, tests hand in
 * fakes.
 *
 * The lockfile is the truth here too: when dependency locking pins a
 * coordinate at a different version than the graph resolved, the exported
 * component carries the LOCKED version (that is what the build runs), and
 * the graph's answer survives as a property so the drift stays visible.
 * Locks only override versions; they never add artifacts, because an
 * artifact with no graph edge would be a guess about how it arrived.
 */
object SbomGraph {

    data class Artifact(
        val groupId: String,
        val artifactId: String,
        val version: String,
        /** The version the resolved graph reported, when a lock overrode it. */
        val graphVersion: String? = null,
    ) {
        val key: String get() = "$groupId:$artifactId:$version"
    }

    data class Result(
        val artifacts: List<Artifact>,
        /** artifact key -> child artifact keys, insertion-ordered. */
        val dependsOn: Map<String, List<String>>,
        /** Direct dependencies: the roots' immediate resolved children. */
        val rootDependsOn: List<String>,
    ) {
        val isEmpty: Boolean get() = artifacts.isEmpty()
    }

    private const val MAX_DEPTH = 64

    fun collect(roots: List<ProvenanceTrace.Node>, locked: List<Lockfile.Locked> = emptyList()): Result {
        val lockedVersions = mutableMapOf<Pair<String, String>, String>()
        for (lock in locked) lockedVersions.putIfAbsent(lock.group to lock.name, lock.version)

        val artifacts = linkedMapOf<String, Artifact>()
        val edges = linkedMapOf<String, LinkedHashSet<String>>()
        val rootChildren = LinkedHashSet<String>()
        val walked = mutableSetOf<String>()

        fun resolvedArtifact(node: ProvenanceTrace.Node): Artifact {
            val lockedVersion = lockedVersions[node.groupId to node.artifactId]
            return if (lockedVersion != null && lockedVersion != node.version) {
                Artifact(node.groupId, node.artifactId, lockedVersion, graphVersion = node.version)
            } else {
                Artifact(node.groupId, node.artifactId, node.version)
            }
        }

        /** Returns the keys this node contributes to its parent's dependsOn. */
        fun walk(node: ProvenanceTrace.Node, depth: Int): List<String> {
            if (depth > MAX_DEPTH) return emptyList()
            // Evicted or unresolved occurrences never ship; their subtrees
            // belong to the winning occurrence, which is walked elsewhere.
            if (!node.winner) return emptyList()
            // A module hop is a pass-through: its library children attach to
            // whatever depended on the module.
            if (node.groupId.isEmpty()) {
                return node.children.flatMap { walk(it, depth + 1) }
            }
            val artifact = resolvedArtifact(node)
            artifacts.putIfAbsent(artifact.key, artifact)
            // The same g:a:v can appear many times across modules; its
            // subtree is identical, so edges are recorded once.
            if (walked.add(artifact.key)) {
                // A cycle would otherwise record a self-edge on re-entry.
                val children = node.children.flatMap { walk(it, depth + 1) }.filter { it != artifact.key }
                if (children.isNotEmpty()) {
                    edges.getOrPut(artifact.key) { LinkedHashSet() }.addAll(children)
                }
            }
            return listOf(artifact.key)
        }

        for (root in roots) {
            rootChildren += walk(root, 0)
        }
        return Result(
            artifacts = artifacts.values.toList(),
            dependsOn = edges.mapValues { it.value.toList() },
            rootDependsOn = rootChildren.toList(),
        )
    }
}
