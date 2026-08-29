package com.tampwell.staleguard.impact

/**
 * Turns a linkage finding into the fix: the earliest version of the broken
 * jar's artifact that satisfies everything the classpath calls.
 *
 * Pure search over an injected probe, because each probe costs a jar
 * download in production and the search strategy is exactly what needs
 * testing. Members mostly only ever get added going forward, so a binary
 * search usually lands in log2(N) probes — but monotonicity can lie (a
 * member removed again later), so the answer is verified directly and a
 * contradiction falls back to a forward linear walk. Probes are capped:
 * past the cap the answer is "no clean version found", never a guess.
 */
object FixResolver {

    /** What the candidate version must provide to count as the fix. */
    data class Needs(
        /** Broken refs whose owner lives in this artifact: owner internal name to missing members. */
        val members: Map<String, Set<MemberKey>>,
        /** Evicted classes referenced from elsewhere that this artifact must contain. */
        val classes: Set<String>,
    ) {
        val isEmpty: Boolean get() = members.isEmpty() && classes.isEmpty()
    }

    sealed interface Result {
        /** The earliest probed version that satisfies every need. */
        data class FixedIn(val version: String, val probes: Int) : Result

        /** No candidate satisfied the needs within the probe budget. */
        data class NoCleanVersion(val probes: Int) : Result
    }

    const val MAX_PROBES = 8

    /**
     * [candidates] must be newer-than-current, ascending. [probe] returns the
     * candidate's scans, or null when the jar cannot be fetched — an
     * unfetchable candidate counts as unsatisfying, which biases toward
     * "no clean version" rather than a recommendation nobody verified.
     */
    fun resolve(needs: Needs, candidates: List<String>, probe: (String) -> LinkageAudit.JarScans?): Result {
        if (needs.isEmpty || candidates.isEmpty()) return Result.NoCleanVersion(probes = 0)

        var probes = 0
        val verdictCache = HashMap<String, Boolean>()
        fun satisfied(version: String): Boolean = verdictCache.getOrPut(version) {
            probes++
            val scans = probe(version) ?: return@getOrPut false
            satisfies(needs, scans)
        }

        // Binary search for the first satisfying version, budget-bounded.
        var low = 0
        var high = candidates.size - 1
        var best: Int? = null
        while (low <= high && probes < MAX_PROBES) {
            val mid = (low + high) / 2
            if (satisfied(candidates[mid])) {
                best = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }

        val found = best
        if (found != null) {
            // The lie check: binary search assumed everything after a
            // satisfying version also satisfies. The earliest answer itself
            // was probed directly, so it stands on its own; what needs
            // verifying is that no earlier candidate was skipped past by a
            // non-monotonic middle. A single confirmation probe of the
            // predecessor settles it within budget.
            val predecessor = found - 1
            if (predecessor >= 0 && probes < MAX_PROBES && satisfied(candidates[predecessor])) {
                var earliest = predecessor
                while (earliest - 1 >= 0 && probes < MAX_PROBES && satisfied(candidates[earliest - 1])) {
                    earliest--
                }
                return Result.FixedIn(candidates[earliest], probes)
            }
            return Result.FixedIn(candidates[found], probes)
        }

        // Binary search found nothing inside the budget. The newest candidate
        // is the last hope worth one probe: if even it does not satisfy,
        // "no clean version" is simply true as far as anyone has checked.
        if (probes < MAX_PROBES && satisfied(candidates.last())) {
            return Result.FixedIn(candidates.last(), probes)
        }
        return Result.NoCleanVersion(probes)
    }

    /**
     * Whether [scans] provides every needed class and member. Members resolve
     * through the candidate's own hierarchy; a hierarchy that escapes the
     * candidate jar proves nothing and counts as UNSATISFIED, because a fix
     * suggestion is a recommendation and recommendations need proof.
     */
    fun satisfies(needs: Needs, scans: LinkageAudit.JarScans): Boolean {
        val byName = scans.classes.associateBy { it.internalName }
        if (!needs.classes.all { it in byName }) return false
        return needs.members.all { (owner, members) ->
            members.all { member -> resolvesWithin(byName, owner, member) }
        }
    }

    private fun resolvesWithin(byName: Map<String, ClassScan>, owner: String, member: MemberKey): Boolean {
        val seen = HashSet<String>()
        val queue = ArrayDeque<String>()
        queue += owner
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current)) continue
            val scan = byName[current] ?: continue
            if (member in scan.declaredAll) return true
            scan.api.superName?.let { queue += it }
            queue += scan.api.interfaces
        }
        return false
    }
}
