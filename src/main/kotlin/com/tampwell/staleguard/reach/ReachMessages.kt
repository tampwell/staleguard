package com.tampwell.staleguard.reach

import com.tampwell.staleguard.StaleguardBundle

/** How verdicts read in the tool window. One place, so the wording cannot drift between surfaces. */
object ReachMessages {

    fun reason(reason: ReachVerdict.Reason): String = StaleguardBundle.message("reach.reason.${reason.name}")

    /** Most urgent first: reached by shipped code, by tests only, undetermined, not reached. */
    fun rank(verdict: ReachVerdict): Int = when (verdict) {
        is ReachVerdict.Reached -> if (verdict.inProduction) 0 else 1
        is ReachVerdict.Unknown -> 2
        is ReachVerdict.NotReached -> 3
    }

    fun row(artifact: String, advisory: String, verdict: ReachVerdict): String = when (verdict) {
        is ReachVerdict.Reached -> StaleguardBundle.message(
            if (verdict.inProduction) "toolwindow.reach.reached" else "toolwindow.reach.tests",
            artifact, advisory, verdict.path.joinToString(" -> "),
        )
        is ReachVerdict.NotReached -> StaleguardBundle.message("toolwindow.reach.notreached", artifact, advisory, verdict.touchedTotal)
        is ReachVerdict.Unknown -> StaleguardBundle.message("toolwindow.reach.unknown", artifact, advisory, reason(verdict.reason))
    }

    fun header(age: String, verdicts: Collection<ReachVerdict>): String = StaleguardBundle.message(
        "toolwindow.reach",
        age,
        verdicts.count { it is ReachVerdict.Reached && it.inProduction },
        verdicts.count { it is ReachVerdict.Reached && !it.inProduction },
        verdicts.count { it is ReachVerdict.NotReached },
        verdicts.count { it is ReachVerdict.Unknown },
    )
}
