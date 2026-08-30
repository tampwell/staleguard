package com.tampwell.staleguard.impact

/**
 * What changed between two audits — the piece that decides whether the user
 * hears about it.
 *
 * The rule is the one the advisory notifications proved out: discovery is not
 * news. The first audit after a project opens establishes a baseline
 * silently; only findings that were absent last time deserve a notification,
 * because those are the ones the sync that just ran introduced. Findings that
 * disappear need no fanfare either — the dialog shows the current truth on
 * demand.
 */
object LinkageDelta {

    /** A finding's identity across runs: what broke, not how many call sites hit it. */
    data class Key(val fromJar: String, val detail: String)

    data class Delta(val newBroken: List<LinkageAudit.BrokenRef>, val newEvicted: List<LinkageAudit.EvictedClassRefs>) {
        val isNews: Boolean get() = newBroken.isNotEmpty() || newEvicted.isNotEmpty()
        val count: Int get() = newBroken.size + newEvicted.size
    }

    fun keyOf(finding: LinkageAudit.BrokenRef): Key =
        Key(finding.fromJar, "${finding.ref.owner}#${finding.ref.name}${finding.ref.descriptor}")

    fun keyOf(finding: LinkageAudit.EvictedClassRefs): Key = Key(finding.fromJar, finding.owner)

    fun fingerprint(report: LinkageAudit.Report): Set<Key> =
        report.brokenMembers.mapTo(HashSet(), ::keyOf) + report.evictedClasses.mapTo(HashSet(), ::keyOf)

    fun newSince(previous: Set<Key>, current: LinkageAudit.Report): Delta = Delta(
        newBroken = current.brokenMembers.filter { keyOf(it) !in previous }.distinctBy(::keyOf),
        newEvicted = current.evictedClasses.filter { keyOf(it) !in previous },
    )
}
