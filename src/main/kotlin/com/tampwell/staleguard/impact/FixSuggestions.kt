package com.tampwell.staleguard.impact

import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.version.MavenVersion

/**
 * Assembles [FixResolver.Needs] from a linkage report and resolves each broken
 * jar to its fix. Orchestration only, over injected seams, because every seam
 * is either I/O (versions, probes) or policy — and the assembly rules are what
 * need testing:
 *
 *  - Needs group by the jar whose version has to move: broken members by the
 *    jar that failed to declare them, evicted classes by the jar that owns
 *    their package.
 *  - A jar that cannot be identified as Maven coordinates gets no suggestion.
 *  - Candidates are versions newer than the resolved one, in Maven order,
 *    filtered through the project's pin policy first: the audit must never
 *    suggest what .staleguard.toml forbids.
 *  - No cached version list means no suggestion this run. The list arrives
 *    with the plugin's normal lookups; a fix hint is not worth a surprise
 *    network fan-out.
 */
object FixSuggestions {

    sealed interface Suggestion {
        data class FixedIn(val version: String) : Suggestion
        data object NoCleanVersion : Suggestion
    }

    /** Everything the computation touches outside pure logic. */
    class Sources(
        /** Maven identity of a jar on the classpath, by the jar name the report uses. */
        val identify: (jarName: String) -> JarCoordinates.Identified?,
        /** The jar owning classes of this package, for attributing evicted classes. */
        val packageOwner: (packageName: String) -> String?,
        /** Cached-only version list for an artifact; null when never resolved. */
        val versionsFor: (Coordinates) -> List<String>?,
        /** The project's pin policy, same predicate the upgrade hints use. */
        val versionAllowed: (Coordinates, current: MavenVersion?, candidate: MavenVersion) -> Boolean,
        /** Fetch and scan one candidate version. Expensive; the resolver budgets it. */
        val probe: (Coordinates, version: String) -> LinkageAudit.JarScans?,
    )

    fun compute(report: LinkageAudit.Report, sources: Sources): Map<String, Suggestion> {
        val needsByJar = HashMap<String, Pair<MutableMap<String, MutableSet<MemberKey>>, MutableSet<String>>>()

        for (broken in report.brokenMembers) {
            val ownerJar = broken.ownerJar ?: continue
            val (members, _) = needsByJar.getOrPut(ownerJar) { HashMap<String, MutableSet<MemberKey>>() to HashSet() }
            members.getOrPut(broken.ref.owner) { LinkedHashSet() } += broken.ref.key
        }
        for (evicted in report.evictedClasses) {
            val pkg = evicted.owner.substringBeforeLast('/', "")
            val ownerJar = sources.packageOwner(pkg) ?: continue
            val (_, classes) = needsByJar.getOrPut(ownerJar) { HashMap<String, MutableSet<MemberKey>>() to HashSet() }
            classes += evicted.owner
        }

        val suggestions = HashMap<String, Suggestion>()
        for ((jarName, needsPair) in needsByJar) {
            val identified = sources.identify(jarName) ?: continue
            val current = MavenVersion(identified.version)
            val candidates = sources.versionsFor(identified.coordinates)
                ?.map(::MavenVersion)
                ?.filter { it > current }
                ?.filter { sources.versionAllowed(identified.coordinates, current, it) }
                ?.sorted()
                ?.map { it.value }
                ?.takeIf { it.isNotEmpty() }
                ?: continue

            val result = FixResolver.resolve(
                FixResolver.Needs(needsPair.first, needsPair.second),
                candidates,
            ) { version -> sources.probe(identified.coordinates, version) }

            suggestions[jarName] = when (result) {
                is FixResolver.Result.FixedIn -> Suggestion.FixedIn(result.version)
                is FixResolver.Result.NoCleanVersion -> Suggestion.NoCleanVersion
            }
        }
        return suggestions
    }
}
