package com.tampwell.staleguard.reach

import com.tampwell.staleguard.impact.MemberRef
import com.tampwell.staleguard.version.MavenVersion
import com.tampwell.staleguard.version.isStable

/**
 * Chooses what to diff so that "what the fix changed" is the fix.
 *
 * Diffing the project's version against the fixed one captures every change
 * in between; for a project several releases behind that is most of the
 * library, and every verdict collapses to "reached". The last stable release
 * before the fix, diffed against the fix, isolates the fix itself. Those
 * methods are then looked up in the project's own version, and only when
 * every one of them exists there under the same identity; otherwise the
 * project may carry the same logic under another name, and only the wide
 * diff is safe.
 */
object FixWindow {

    /**
     * The last stable release before [fixed], when it is newer than the
     * project's [vulnerable] version. Prereleases never qualify: a candidate
     * of the fixed release may already contain the fix and would hide it.
     * Null means "diff the project's own version": it already sits at the
     * baseline, or the release list is not known.
     */
    fun baseline(versions: List<MavenVersion>, vulnerable: String, fixed: String): String? {
        val fixedVersion = MavenVersion(fixed)
        val current = MavenVersion(vulnerable)
        val before = versions.filter { it.isStable && it < fixedVersion }.maxOrNull() ?: return null
        return before.value.takeIf { before > current }
    }

    /**
     * [touched] as methods of the project's version, or null when any of
     * them is missing from it. An empty set maps to itself: a fix that
     * changed no code is a real answer, not a reason to widen.
     */
    fun mapOnto(touched: Set<MemberRef>, vulnerable: ClassSource): Set<MemberRef>? {
        for ((owner, methods) in touched.groupBy { it.owner }) {
            val bytes = vulnerable.bytes(owner) ?: return null
            val declared = runCatching { ClassBodyReader.read(bytes, scanBodies = false).methods.keys }.getOrNull()
                ?: return null
            if (methods.any { it.key !in declared }) return null
        }
        return touched
    }
}
