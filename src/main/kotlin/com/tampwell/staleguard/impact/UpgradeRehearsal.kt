package com.tampwell.staleguard.impact

/**
 * Rehearses an upgrade against the full classpath audit: the current jar's
 * scans are swapped for the candidate version's and every scope is resolved
 * again. The diff of the two verdicts answers what no per-member impact check
 * can — whether this upgrade fixes the linkage problems the doctor already
 * knows about, and whether it introduces new ones anywhere on any module's
 * classpath, not just at the members this project calls directly.
 *
 * The replacement keeps the current jar's NAME so finding identity is stable
 * across the diff; the report's wording supplies the "after the upgrade"
 * framing.
 */
object UpgradeRehearsal {

    data class Verdict(
        val fixedBroken: List<LinkageAudit.BrokenRef>,
        val fixedEvicted: List<LinkageAudit.EvictedClassRefs>,
        val fixedShadowed: List<ShadowAudit.ShadowGroup>,
        val introduced: LinkageDelta.Delta,
    ) {
        val fixedCount: Int get() = fixedBroken.size + fixedEvicted.size + fixedShadowed.size

        val fixedLines: List<String>
            get() = fixedBroken.map { "${it.fromJar}: ${it.ref.display()}" } +
                fixedEvicted.map { "${it.fromJar}: ${it.owner.replace('/', '.')}" } +
                fixedShadowed.map { "${it.winnerJar} vs ${it.shadowedJars.joinToString(", ")}" }

        val introducedLines: List<String>
            get() = introduced.newBroken.map { "${it.fromJar}: ${it.ref.display()}" } +
                introduced.newEvicted.map { "${it.fromJar}: ${it.owner.replace('/', '.')}" } +
                introduced.newShadowed.map { "${it.winnerJar} vs ${it.shadowedJars.joinToString(", ")}" }
    }

    fun rehearse(
        scopes: List<ScopedLinkage.Scope>,
        currentJarName: String,
        replacement: LinkageAudit.JarScans,
        platformMembers: (internalName: String, memberName: String) -> Boolean,
    ): Verdict {
        val before = ScopedLinkage.run(scopes, platformMembers).report
        val relabeled = LinkageAudit.JarScans(currentJarName, replacement.classes)
        val upgraded = scopes.map { scope ->
            ScopedLinkage.Scope(scope.name, scope.jars.map { if (it.jarName == currentJarName) relabeled else it })
        }
        val after = ScopedLinkage.run(upgraded, platformMembers).report

        val afterKeys = LinkageDelta.fingerprint(after)
        return Verdict(
            fixedBroken = before.brokenMembers.filter { LinkageDelta.keyOf(it) !in afterKeys }
                .distinctBy { LinkageDelta.keyOf(it) },
            fixedEvicted = before.evictedClasses.filter { LinkageDelta.keyOf(it) !in afterKeys },
            fixedShadowed = before.shadowedGroups.filter { LinkageDelta.keyOf(it) !in afterKeys },
            introduced = LinkageDelta.newSince(LinkageDelta.fingerprint(before), after),
        )
    }
}
