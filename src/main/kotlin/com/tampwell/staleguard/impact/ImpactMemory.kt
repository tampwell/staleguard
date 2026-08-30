package com.tampwell.staleguard.impact

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.tampwell.staleguard.plan.MeasuredImpact
import java.util.concurrent.ConcurrentHashMap

/**
 * What this session's impact analyses found, so the answer shows up where the
 * user actually works instead of only in the dialog that produced it.
 *
 * In memory only, deliberately. [RemovedMembersCache] persists the binary diff
 * because released versions never change, but which call sites a project has
 * is a property of source that is being edited right now — persisting it would
 * mean confidently reporting call sites that were deleted three days ago.
 *
 * The read path is a map lookup with no I/O, which is what lets highlighting
 * use it: the warm-cache-only invariant covers this the same way it covers
 * version and advisory peeks.
 */
@Service(Service.Level.PROJECT)
class ImpactMemory {

    private data class Key(val coordinate: String, val from: String, val to: String)

    private val results = ConcurrentHashMap<Key, MeasuredImpact>()

    fun record(report: ImpactReport) {
        val measured = classify(report)
        if (measured == MeasuredImpact.Unknown) return
        results[Key(report.coordinate, report.fromVersion, report.toVersion)] = measured
    }

    /** I/O-free lookup, safe to call from a highlighting pass. */
    fun measured(coordinate: String, from: String, to: String): MeasuredImpact =
        results[Key(coordinate, from, to)] ?: MeasuredImpact.Unknown

    /** The shape [com.tampwell.staleguard.plan.UpgradePlanner] takes, so the plan layer stays platform-free. */
    fun lookup(): (String, String, String) -> MeasuredImpact =
        { coordinate, from, to -> measured(coordinate, from, to) }

    companion object {
        fun getInstance(project: Project): ImpactMemory = project.service()

        /**
         * What a report is allowed to claim. Pure, so the honesty rules are
         * tested directly rather than through a live project service.
         *
         * An analysis that could not finish, or one whose search was cut
         * short, returns Unknown: recording either would let "checked" appear
         * beside an upgrade that was never fully checked, and a false all-clear
         * is the one failure this feature cannot afford.
         */
        fun classify(report: ImpactReport): MeasuredImpact = when {
            report.incomplete != null || report.searchTruncated -> MeasuredImpact.Unknown
            report.usages.isNotEmpty() -> MeasuredImpact.Breaks(report.usages.size, report.affectedCallSites)
            // My own calls survive, but the rehearsal saw the wider classpath
            // break. A missing rehearsal stays Clean: absence of a check is
            // not evidence, and the member analysis itself DID complete.
            (report.rehearsal?.introduced?.size ?: 0) > 0 ->
                MeasuredImpact.BreaksLinkage(report.rehearsal!!.introduced.size)
            else -> MeasuredImpact.Clean
        }
    }
}
