package com.tampwell.staleguard.impact

/**
 * The impact report as Markdown, for pasting into a pull request, an issue, or
 * a team chat. Pure string work: the dialog shows the report to one person,
 * and this is how it reaches the rest of the team.
 */
object ImpactMarkdown {

    fun render(report: ImpactReport): String = buildString {
        appendLine("### Upgrade impact: ${report.coordinate} ${report.fromVersion} -> ${report.toVersion}")
        appendLine()
        when (report.verdict) {
            ImpactVerdict.BREAKS -> {
                appendLine(
                    "Calls **${report.usages.size}** removed ${plural(report.usages.size, "member")} " +
                        "at **${report.affectedCallSites}** call ${plural(report.affectedCallSites, "site")} " +
                        "(${report.removedTotal} public ${plural(report.removedTotal, "member")} removed in total).",
                )
                appendLine()
                for (usage in report.usages) {
                    appendLine("- `${usage.member.display()}`")
                    for (location in usage.locations) {
                        appendLine("  - ${location.presentablePath}:${location.line}")
                    }
                }
            }

            ImpactVerdict.REMOVALS_UNUSED -> appendLine(
                "Removes ${report.removedTotal} public ${plural(report.removedTotal, "member")}; " +
                    "this project calls none of them.",
            )

            ImpactVerdict.NO_REMOVALS -> appendLine("Removes no public API. Existing code keeps linking.")

            // An unfinished analysis exported as text would read as a result
            // in the PR it gets pasted into, so it names itself incomplete.
            ImpactVerdict.UNKNOWN -> appendLine("Analysis incomplete; no conclusion.")
        }
        if (report.searchTruncated) {
            appendLine()
            appendLine("_Search truncated; the list above may be incomplete._")
        }
        appendLine()
        appendLine("_Staleguard upgrade impact check, compared at the bytecode level._")
    }.trimEnd() + "\n"

    private fun plural(count: Int, word: String) = if (count == 1) word else word + "s"
}
