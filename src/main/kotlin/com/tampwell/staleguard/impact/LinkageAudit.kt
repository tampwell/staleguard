package com.tampwell.staleguard.impact

/**
 * Predicts linkage failures across a resolved classpath before anything runs:
 * the NoSuchMethodError and NoClassDefFoundError a version conflict produces
 * when nearest-wins evicted the version somebody was compiled against.
 *
 * Pure logic over [ClassScan]s. The two verdict kinds are deliberately
 * separate, because they demand different fixes: a missing member means two
 * jars disagree about a version, while a missing class whose package is
 * partially present means the resolved version simply predates it.
 *
 * Noise rules, each one paid for during the spike (docs/spike-classpath-doctor.md):
 *  - JDK members must be resolved for real via [platformMembers]; "the
 *    hierarchy reached Object" describes every class alive, and treating it
 *    as resolved makes the whole audit vacuous.
 *  - A missing owner is only an eviction when its FULL package is partially
 *    present; prefix matching accused optional dependencies inside umbrella
 *    namespaces.
 *  - Refs from Groovy runtime classes are skipped: Groovy dispatches through
 *    its own runtime, and its constant pool advertises calls that never go
 *    through JVM linkage.
 *  - An owner absent along with its whole package proves nothing (an optional
 *    or provided dependency) and reports nothing.
 */
object LinkageAudit {

    /** One jar's worth of scans, keyed the way the report groups. */
    data class JarScans(val jarName: String, val classes: List<ClassScan>)

    /** A member call that cannot resolve anywhere on this classpath. */
    data class BrokenRef(val fromJar: String, val ref: MemberRef, val ownerJar: String?)

    /** Calls into a class the classpath does not have, though its package is. */
    data class EvictedClassRefs(val fromJar: String, val owner: String, val refCount: Int)

    data class Report(
        val jarCount: Int,
        val classCount: Int,
        val refCount: Int,
        val brokenMembers: List<BrokenRef>,
        val evictedClasses: List<EvictedClassRefs>,
        /**
         * Duplicate classes with differing APIs ([ShadowAudit]). Latent, not
         * broken calls, so they do not unset [clean] — but they render, and
         * the watcher treats a newly appearing group as news.
         */
        val shadowedGroups: List<ShadowAudit.ShadowGroup> = emptyList(),
    ) {
        val clean: Boolean get() = brokenMembers.isEmpty() && evictedClasses.isEmpty()
    }

    /**
     * [platformMembers] answers "does this JDK type declare a member with this
     * name" — the production caller backs it with the project SDK index, tests
     * with a map. Name-only on purpose: descriptor-exact JDK matching buys
     * almost nothing and the under-report direction is the safe one.
     */
    fun run(
        jars: List<JarScans>,
        platformMembers: (internalName: String, memberName: String) -> Boolean,
    ): Report {
        val byName = HashMap<String, ClassScan>()
        val ownerJar = HashMap<String, String>()
        for (jar in jars) {
            for (scan in jar.classes) {
                if (byName.putIfAbsent(scan.internalName, scan) == null) {
                    ownerJar[scan.internalName] = jar.jarName
                }
            }
        }
        val packagesPresent = byName.keys.mapTo(HashSet()) { packageOf(it) }

        val broken = mutableListOf<BrokenRef>()
        val evicted = LinkedHashMap<Pair<String, String>, Int>()
        var refCount = 0

        for (jar in jars) {
            for (scan in jar.classes) {
                if (isGroovyRuntime(scan.internalName)) continue
                for (ref in scan.refs) {
                    refCount++
                    val owner = byName[ref.owner]
                    if (owner == null) {
                        if (ApiSurface.isPlatformType(ref.owner)) continue
                        if (packageOf(ref.owner) in packagesPresent) {
                            evicted.merge(jar.jarName to ref.owner, 1, Int::plus)
                        }
                        continue
                    }
                    if (!resolves(byName, ref, platformMembers)) {
                        broken += BrokenRef(jar.jarName, ref, ownerJar[ref.owner])
                    }
                }
            }
        }
        return Report(
            jarCount = jars.size,
            classCount = byName.size,
            refCount = refCount,
            brokenMembers = broken,
            evictedClasses = evicted.map { (key, count) -> EvictedClassRefs(key.first, key.second, count) },
        )
    }

    private fun resolves(
        byName: Map<String, ClassScan>,
        ref: MemberRef,
        platformMembers: (String, String) -> Boolean,
    ): Boolean {
        val seen = HashSet<String>()
        val queue = ArrayDeque<String>()
        queue += ref.owner
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current)) continue
            val scan = byName[current]
            if (scan == null) {
                if (ApiSurface.isPlatformType(current)) {
                    if (platformMembers(current, ref.name)) return true
                    // The JDK's own super chain is the platform's to walk;
                    // platformMembers is expected to cover inherited members.
                    continue
                }
                // A non-JDK supertype outside the classpath: cannot prove
                // absence of anything. Uncertainty is not an accusation.
                return true
            }
            if (ref.key in scan.declaredAll) return true
            scan.api.superName?.let { queue += it }
            queue += scan.api.interfaces
        }
        return false
    }

    private fun packageOf(internalName: String): String =
        internalName.substringBeforeLast('/', "")

    internal fun isGroovyRuntime(internalName: String): Boolean =
        internalName.startsWith("org/codehaus/groovy/") || internalName.startsWith("groovy/")
}
