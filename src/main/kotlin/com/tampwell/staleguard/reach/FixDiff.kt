package com.tampwell.staleguard.reach

import com.tampwell.staleguard.impact.MemberRef

/**
 * What a fix release actually changed, method by method: the methods of the
 * vulnerable version whose behavior the fixed version replaced or removed.
 *
 * This is deliberately "fix-touched", not "vulnerable": no free data source
 * maps advisories to methods, and a release diff states a fact about the
 * artifacts rather than a guess about intent. The error direction is chosen:
 * anything that makes two versions differ counts, so unrelated changes
 * shipped alongside the fix widen the set, and a verdict built on it errs
 * toward "reached". A fix that changes no bytecode at all (a resource, a
 * default in a properties file) produces an empty set, which callers must
 * treat as "cannot tell", never as "not reached".
 */
object FixDiff {

    class Result(
        /** Methods of the vulnerable version the fix release changed or removed. */
        val touched: Set<MemberRef>,
        val methodsCompared: Int,
        val classesRemoved: Int,
        val classesChanged: Int,
        /** Vulnerable-side classes that could not be read; their methods are unknown, not unchanged. */
        val unreadable: List<String>,
    ) {
        val touchedRatio: Double
            get() = if (methodsCompared == 0) 0.0 else touched.size.toDouble() / methodsCompared
    }

    fun compare(vulnerable: ClassSource, fixed: ClassSource): Result {
        val touched = LinkedHashSet<MemberRef>()
        val unreadable = ArrayList<String>()
        var compared = 0
        var removed = 0
        var changed = 0
        for (name in vulnerable.classNames) {
            val before = vulnerable.bytes(name)
                ?.let { runCatching { ClassBodyReader.fingerprints(it) }.getOrNull() }
            if (before == null) {
                unreadable += name
                continue
            }
            compared += before.methods.size
            val after = fixed.bytes(name)?.let { runCatching { ClassBodyReader.fingerprints(it) }.getOrNull() }
            // Gone, unreadable, or re-parented: every method may now behave
            // differently, because dispatch and inheritance moved under it.
            if (after == null || after.header.superName != before.header.superName ||
                after.header.interfaces.toSet() != before.header.interfaces.toSet()
            ) {
                if (after == null) removed++ else changed++
                before.methods.keys.mapTo(touched) { MemberRef(name, it) }
                continue
            }
            var classChanged = false
            for ((key, fingerprint) in before.methods) {
                if (after.methods[key] != fingerprint) {
                    touched += MemberRef(name, key)
                    classChanged = true
                }
            }
            if (classChanged) changed++
        }
        return Result(touched, compared, removed, changed, unreadable)
    }
}
