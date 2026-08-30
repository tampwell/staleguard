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

    fun fingerprint(report: LinkageAudit.Report): Set<Key> =
        report.brokenMembers.mapTo(HashSet()) { Key(it.fromJar, it.ref.toStringKey()) } +
            report.evictedClasses.mapTo(HashSet()) { Key(it.fromJar, it.owner) }

    fun newSince(previous: Set<Key>, current: LinkageAudit.Report): Delta = Delta(
        newBroken = current.brokenMembers
            .filter { Key(it.fromJar, it.ref.toStringKey()) !in previous }
            .distinctBy { Key(it.fromJar, it.ref.toStringKey()) },
        newEvicted = current.evictedClasses.filter { Key(it.fromJar, it.owner) !in previous },
    )

    private fun MemberRef.toStringKey(): String = "$owner#$name$descriptor"
}
