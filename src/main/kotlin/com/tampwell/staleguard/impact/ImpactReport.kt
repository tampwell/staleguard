package com.tampwell.staleguard.impact

/** One place in the user's own source that references a member the upgrade removes. */
data class UsageLocation(
    /** VFS url, so the dialog can re-resolve the file without the model depending on the platform. */
    val fileUrl: String,
    val presentablePath: String,
    val line: Int,
    val offset: Int,
)

/** A removed member together with every call site found for it. */
data class RemovedUsage(val member: MemberRef, val locations: List<UsageLocation>)

/**
 * What an upgrade would do to this project.
 *
 * [removedTotal] counts every public member the new version drops; [usages]
 * counts only the ones this project actually calls. The gap between them is
 * the point of the feature: "197 members removed" is noise, "2 of them are
 * called from your code, here" is an answer.
 */
data class ImpactReport(
    val coordinate: String,
    val fromVersion: String,
    val toVersion: String,
    val removedTotal: Int,
    val usages: List<RemovedUsage>,
    /** Set when the analysis could not run to completion; the report then states why rather than claiming safety. */
    val incomplete: Incomplete? = null,
    /** True when the removal list was too long to search in full; the report says so instead of implying "all clear". */
    val searchTruncated: Boolean = false,
) {
    val verdict: ImpactVerdict get() = ImpactVerdict.of(this)

    val affectedCallSites: Int get() = usages.sumOf { it.locations.size }

    enum class Incomplete {
        /** The current version's jar was not on disk and could not be fetched. */
        CURRENT_JAR_UNAVAILABLE,

        /** The candidate version's jar could not be fetched. */
        CANDIDATE_JAR_UNAVAILABLE,

        /** Offline mode, or the network refused. */
        OFFLINE,
    }
}

enum class ImpactVerdict {
    /** Analysis did not finish; no safety claim is made. */
    UNKNOWN,

    /** The new version removes nothing public. Binary-compatible for callers. */
    NO_REMOVALS,

    /** Public members were removed, but this project calls none of them. */
    REMOVALS_UNUSED,

    /** This project calls members the new version removes. */
    BREAKS,
    ;

    companion object {
        fun of(report: ImpactReport): ImpactVerdict = when {
            report.incomplete != null -> UNKNOWN
            report.usages.isNotEmpty() -> BREAKS
            report.removedTotal == 0 -> NO_REMOVALS
            else -> REMOVALS_UNUSED
        }
    }
}
