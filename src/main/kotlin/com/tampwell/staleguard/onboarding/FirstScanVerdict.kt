package com.tampwell.staleguard.onboarding

import com.tampwell.staleguard.plan.ModuleStats

/**
 * Whether the first completed scan of a project deserves a word.
 *
 * A project where everything is current shows the user NOTHING: the
 * inspections only warn, and the status bar hides itself when there is
 * nothing to act on. Silence is indistinguishable from a broken plugin,
 * which is the worst possible first impression. Exactly one notification
 * closes that gap, and only in the case that would otherwise be silent.
 */
internal object FirstScanVerdict {

    enum class Verdict {
        /** Not finished scanning: say nothing yet, wait for the next event. */
        WAIT,

        /** Everything is current — the one case the user would otherwise never see. */
        NOTIFY_CLEAN,

        /** Findings exist; the editor warnings and the counter speak for themselves. */
        STAY_SILENT,
    }

    fun of(summary: ModuleStats?, pendingLookups: Boolean): Verdict = when {
        pendingLookups -> Verdict.WAIT
        summary == null || summary.totalDependencies == 0 -> Verdict.WAIT
        // Unresolved coordinates mean the answer is still incomplete. Claiming
        // "everything is current" while lookups failed would be a lie.
        summary.unresolved > 0 -> Verdict.WAIT
        summary.totalUpdates == 0 && summary.abandoned == 0 && summary.vulnerable == 0 -> Verdict.NOTIFY_CLEAN
        else -> Verdict.STAY_SILENT
    }
}
