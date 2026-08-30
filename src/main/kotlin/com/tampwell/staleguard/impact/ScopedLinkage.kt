package com.tampwell.staleguard.impact

/**
 * Runs the linkage audit once per module scope and merges the verdicts.
 *
 * A project-wide union classpath is a set no JVM ever loads: each module runs
 * against its own resolved classpath. The union stays silent about a real
 * failure whenever a class is missing from the module that needs it but
 * present in a sibling's classpath, and its first-jar-wins duplicate picking
 * can crown a version no module actually resolves. Auditing per scope is the
 * truth; merging keeps one finding per identity, so the report, the delta
 * watcher, and fix suggestions all keep their shape, and a break that shows
 * up in a second module is the same break, not fresh news.
 */
object ScopedLinkage {

    data class Scope(val name: String, val jars: List<LinkageAudit.JarScans>)

    data class Merged(
        val report: LinkageAudit.Report,
        val moduleCount: Int,
        /** Which scopes each merged finding holds in, by finding identity. */
        val modulesByFinding: Map<LinkageDelta.Key, List<String>>,
    )

    /**
     * [onScopeDone] reports (scopes finished, scopes total) for progress; a
     * cancellation check inside [platformMembers] keeps long runs stoppable.
     */
    fun run(
        scopes: List<Scope>,
        platformMembers: (internalName: String, memberName: String) -> Boolean,
        onScopeDone: (Int, Int) -> Unit = { _, _ -> },
    ): Merged {
        // Scopes sharing a jar set produce identical verdicts; audit each
        // distinct set once and attribute it to every scope that shares it.
        val groups = scopes.groupBy { scope -> scope.jars.mapTo(sortedSetOf()) { it.jarName } }

        val broken = LinkedHashMap<LinkageDelta.Key, LinkageAudit.BrokenRef>()
        val evicted = LinkedHashMap<LinkageDelta.Key, LinkageAudit.EvictedClassRefs>()
        val shadowed = LinkedHashMap<LinkageDelta.Key, ShadowAudit.ShadowGroup>()
        val modules = HashMap<LinkageDelta.Key, MutableList<String>>()
        var done = 0
        for (group in groups.values) {
            val report = LinkageAudit.run(group.first().jars, platformMembers)
            val names = group.map { it.name }
            for (finding in report.brokenMembers) {
                val key = LinkageDelta.keyOf(finding)
                broken.putIfAbsent(key, finding)
                modules.getOrPut(key) { mutableListOf() } += names
            }
            for (finding in report.evictedClasses) {
                val key = LinkageDelta.keyOf(finding)
                // The widest view of the same eviction is the most informative.
                evicted.merge(key, finding) { a, b -> if (a.refCount >= b.refCount) a else b }
                modules.getOrPut(key) { mutableListOf() } += names
            }
            for (finding in ShadowAudit.run(group.first().jars)) {
                val key = LinkageDelta.keyOf(finding)
                shadowed.merge(key, finding) { a, b -> if (a.classCount >= b.classCount) a else b }
                modules.getOrPut(key) { mutableListOf() } += names
            }
            done += group.size
            onScopeDone(done, scopes.size)
        }

        val distinctJars = scopes.flatMap { it.jars }.distinctBy { it.jarName }
        return Merged(
            report = LinkageAudit.Report(
                jarCount = distinctJars.size,
                classCount = distinctJars.asSequence().flatMap { it.classes }.distinctBy { it.internalName }.count(),
                refCount = distinctJars.sumOf { jar ->
                    jar.classes.filterNot { LinkageAudit.isGroovyRuntime(it.internalName) }.sumOf { it.refs.size }
                },
                brokenMembers = broken.values.toList(),
                evictedClasses = evicted.values.toList(),
                shadowedGroups = shadowed.values.toList(),
            ),
            moduleCount = scopes.size,
            modulesByFinding = modules.mapValues { (_, names) -> names.distinct().sorted() },
        )
    }
}
