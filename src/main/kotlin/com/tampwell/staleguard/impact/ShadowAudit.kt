package com.tampwell.staleguard.impact

/**
 * Finds classes that exist in more than one jar with DIFFERING APIs — the
 * duplicates where classpath order silently decides which code runs.
 *
 * Identical copies are harmless and stay silent: on a real 141-jar product
 * classpath, 271 class names were duplicated but only one jar group carried
 * copies that actually differed (the spike that priced this rule). Comparison
 * is the public API surface, not bytes and not the full member table, because
 * recompilation changes synthetics and debug info without changing what a
 * caller can link against.
 *
 * Findings group by the set of jars involved, not per class: one conflicted
 * jar pair with forty-two differing classes is one problem with one fix.
 */
object ShadowAudit {

    /**
     * One group of jars carrying the same classes with differing APIs.
     * [winnerJar] is the copy first-wins resolution actually uses — the same
     * order [LinkageAudit] resolves with.
     */
    data class ShadowGroup(
        val winnerJar: String,
        val shadowedJars: List<String>,
        val classCount: Int,
        /** A few affected class names, dotted, for the report. */
        val examples: List<String>,
    )

    fun run(jars: List<LinkageAudit.JarScans>): List<ShadowGroup> {
        val copies = HashMap<String, MutableList<Pair<String, ClassScan>>>()
        for (jar in jars) {
            for (scan in jar.classes) {
                copies.getOrPut(scan.internalName) { mutableListOf() } += jar.jarName to scan
            }
        }

        // owner -> the jars whose copy differs from the winning copy
        val grouped = LinkedHashMap<Pair<String, List<String>>, MutableList<String>>()
        for ((owner, found) in copies) {
            if (found.size < 2) continue
            val (winnerJar, winner) = found.first()
            val differing = found.asSequence().drop(1)
                .filter { (jarName, copy) -> jarName != winnerJar && apiDiffers(winner, copy) }
                .map { (jarName, _) -> jarName }
                .distinct()
                .sorted()
                .toList()
            if (differing.isEmpty()) continue
            grouped.getOrPut(winnerJar to differing) { mutableListOf() } += owner
        }

        return grouped.map { (key, owners) ->
            ShadowGroup(
                winnerJar = key.first,
                shadowedJars = key.second,
                classCount = owners.size,
                examples = owners.sorted().take(EXAMPLES).map { it.replace('/', '.') },
            )
        }
    }

    private fun apiDiffers(winner: ClassScan, other: ClassScan): Boolean =
        (other.api.members - winner.api.members).isNotEmpty() ||
            other.api.superName != winner.api.superName ||
            other.api.interfaces.toSet() != winner.api.interfaces.toSet()

    private const val EXAMPLES = 3
}
