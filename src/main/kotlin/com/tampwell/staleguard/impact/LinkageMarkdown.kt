package com.tampwell.staleguard.impact

/**
 * The linkage report as Markdown, for the same reason the impact report has
 * one: a classpath conflict is a team conversation, and the paste is how the
 * finding reaches the person who owns the version.
 */
object LinkageMarkdown {

    fun render(
        report: LinkageAudit.Report,
        ownCode: OwnCodeAudit.Standing = OwnCodeAudit.Standing.NothingBuilt,
    ): String = buildString {
        appendLine("### Classpath linkage check")
        appendLine()
        when (ownCode) {
            is OwnCodeAudit.Standing.Built -> appendLine("_Includes your own compiled classes, as of the last build._")
            is OwnCodeAudit.Standing.PartiallyBuilt -> appendLine(
                "_Includes your compiled classes EXCEPT unbuilt ${plural(ownCode.missingModules.size, "module")} " +
                    "${ownCode.missingModules.joinToString(", ")}; their calls were not checked._",
            )
            OwnCodeAudit.Standing.NothingBuilt -> appendLine("_Jars only; no compiled project output was found to include._")
        }
        appendLine()
        if (report.clean) {
            appendLine(
                "Every call across ${report.jarCount} classpath ${plural(report.jarCount, "entry", "entries")} resolves " +
                    "(${report.refCount} references checked). Nothing will fail to link.",
            )
        } else {
            appendLine(
                "**${report.brokenMembers.size}** ${plural(report.brokenMembers.size, "call")} cannot resolve; " +
                    "**${report.evictedClasses.size}** missing ${plural(report.evictedClasses.size, "class")} referenced. " +
                    "These fail at runtime exactly where listed.",
            )
            appendLine()
            for ((fromJar, broken) in report.brokenMembers.groupBy { it.fromJar }) {
                appendLine("- `$fromJar`")
                for (entry in broken.groupBy { it.ref }.entries.sortedByDescending { it.value.size }) {
                    val ownerJar = entry.value.first().ownerJar ?: "?"
                    appendLine("  - `${entry.key.display()}` missing from the resolved `$ownerJar`")
                }
            }
            for (evicted in report.evictedClasses.sortedByDescending { it.refCount }) {
                appendLine(
                    "- `${evicted.owner.replace('/', '.')}` is not on the classpath " +
                        "(${evicted.refCount} ${plural(evicted.refCount, "reference")} from `${evicted.fromJar}`)",
                )
            }
        }
        appendLine()
        appendLine("_Staleguard classpath linkage check, resolved at the bytecode level._")
    }.trimEnd() + "\n"

    private fun plural(count: Int, word: String, pluralForm: String = word + "s") =
        if (count == 1) word else pluralForm
}
