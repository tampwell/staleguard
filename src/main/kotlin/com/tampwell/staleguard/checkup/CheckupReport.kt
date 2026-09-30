package com.tampwell.staleguard.checkup

import kotlin.math.roundToInt

/**
 * One project's dependency health on a single page: every finding
 * Staleguard knows, ranked by what to do first, with a score whose every
 * point is accounted for in [deductions]. Pure, so the ranking and the
 * arithmetic are tested without a project.
 *
 * The score is a summary, not a verdict: it exists so a report reads at a
 * glance and so progress is visible between runs. It never goes up because
 * Staleguard could not look at something: an undetermined or unchecked
 * vulnerability costs exactly as much as a reached one.
 */
object CheckupReport {

    enum class Tier { FIX_NOW, FIX_SOON, REVIEW, ROUTINE }

    enum class Reach { REACHED, TESTS_ONLY, NOT_REACHED, UNDETERMINED, NOT_CHECKED }

    data class Vulnerability(
        val artifact: String,
        val version: String,
        val advisory: String,
        /** CRITICAL / HIGH / MODERATE / LOW as published, null when unrated. */
        val severity: String?,
        val fixedVersion: String?,
        /** False for a transitive: nothing in the build files declares it. */
        val declared: Boolean,
        val reach: Reach,
        /** The call path for REACHED and TESTS_ONLY; the reason for UNDETERMINED. */
        val detail: String? = null,
        /** Opaque navigation target the UI understands; the report only carries it. */
        val target: Any? = null,
    )

    data class Inputs(
        val vulnerabilities: List<Vulnerability> = emptyList(),
        /** Calls that will fail at runtime, one line each, from the linkage doctor. */
        val linkageFailures: List<String> = emptyList(),
        val shadowedGroups: Int = 0,
        val lockDrifts: Int = 0,
        val abandoned: List<String> = emptyList(),
        val majorUpdates: Int = 0,
        val minorUpdates: Int = 0,
        val patchUpdates: Int = 0,
        val dependencies: Int = 0,
        /** False when the linkage doctor could not judge the project's own code (not built). */
        val ownCodeChecked: Boolean = true,
    )

    data class Finding(val tier: Tier, val title: String, val detail: String? = null, val target: Any? = null)

    data class Deduction(val points: Int, val reason: String)

    data class Report(
        val score: Int,
        val grade: String,
        val findings: List<Finding>,
        val deductions: List<Deduction>,
        val inputs: Inputs,
    )

    fun build(inputs: Inputs): Report {
        val findings = ArrayList<Finding>()
        val deductions = ArrayList<Deduction>()

        for (vuln in inputs.vulnerabilities.sortedWith(compareBy({ reachOrder(it.reach) }, { -severityRank(it.severity) }))) {
            val base = severityPoints(vuln.severity)
            // Tests-only halves, not-reached keeps 40%: demoted, never dismissed.
            val points = when (vuln.reach) {
                Reach.REACHED, Reach.UNDETERMINED, Reach.NOT_CHECKED -> base
                Reach.TESTS_ONLY -> (base * 0.5).roundToInt()
                Reach.NOT_REACHED -> (base * 0.4).roundToInt()
            }
            val tier = when (vuln.reach) {
                Reach.REACHED -> Tier.FIX_NOW
                Reach.UNDETERMINED, Reach.NOT_CHECKED ->
                    if (severityRank(vuln.severity) >= 3) Tier.FIX_NOW else Tier.FIX_SOON
                Reach.TESTS_ONLY -> Tier.FIX_SOON
                Reach.NOT_REACHED -> Tier.REVIEW
            }
            val name = "${vuln.artifact} ${vuln.version}"
            val severity = vuln.severity?.lowercase() ?: "unrated"
            val fix = vuln.fixedVersion?.let { ", fixed in $it" } ?: ", no fixed release yet"
            val kind = if (vuln.declared) "" else " (transitive)"
            val reachText = when (vuln.reach) {
                Reach.REACHED -> "your code reaches what the fix changed"
                Reach.TESTS_ONLY -> "only your tests reach what the fix changed"
                Reach.NOT_REACHED -> "no static path from your code reaches what the fix changed"
                Reach.UNDETERMINED -> "reachability undetermined"
                Reach.NOT_CHECKED -> "reachability not checked"
            }
            findings += Finding(
                tier,
                "$name$kind: ${vuln.advisory} ($severity$fix), $reachText",
                vuln.detail,
                vuln.target,
            )
            deductions += Deduction(points, "${vuln.advisory} in $name ($severity, ${reachLabel(vuln.reach)})")
        }

        if (inputs.linkageFailures.isNotEmpty()) {
            for (line in inputs.linkageFailures) findings += Finding(Tier.FIX_NOW, line)
            deductions += Deduction(
                minOf(45, 15 * inputs.linkageFailures.size),
                "${inputs.linkageFailures.size} ${plural(inputs.linkageFailures.size, "call", "calls")} that will fail at runtime",
            )
        }
        if (inputs.shadowedGroups > 0) {
            findings += Finding(
                Tier.REVIEW,
                "${inputs.shadowedGroups} shadowed class ${plural(inputs.shadowedGroups, "group", "groups")}: classpath order decides which copy runs",
            )
            deductions += Deduction(minOf(9, 3 * inputs.shadowedGroups), "${inputs.shadowedGroups} shadowed class groups")
        }
        if (inputs.lockDrifts > 0) {
            findings += Finding(
                Tier.FIX_SOON,
                "${inputs.lockDrifts} ${plural(inputs.lockDrifts, "lock", "locks")} out of step with the build files; re-run Gradle with --write-locks",
            )
            deductions += Deduction(minOf(9, 3 * inputs.lockDrifts), "${inputs.lockDrifts} drifted locks")
        }
        for (name in inputs.abandoned) findings += Finding(Tier.REVIEW, "$name: no release in years, possibly abandoned")
        if (inputs.abandoned.isNotEmpty()) {
            deductions += Deduction(minOf(10, 2 * inputs.abandoned.size), "${inputs.abandoned.size} possibly abandoned dependencies")
        }
        if (inputs.majorUpdates > 0) {
            findings += Finding(Tier.REVIEW, "${inputs.majorUpdates} major ${plural(inputs.majorUpdates, "update", "updates")} available")
            deductions += Deduction(minOf(10, inputs.majorUpdates), "${inputs.majorUpdates} dependencies a major version behind")
        }
        val small = inputs.minorUpdates + inputs.patchUpdates
        if (small > 0) {
            findings += Finding(
                Tier.ROUTINE,
                "${inputs.patchUpdates} patch and ${inputs.minorUpdates} minor ${plural(small, "update", "updates")} available",
            )
            if (small >= 5) deductions += Deduction(minOf(5, small / 5), "$small patch and minor updates pending")
        }

        val score = (100 - deductions.sumOf { it.points }).coerceIn(0, 100)
        return Report(
            score = score,
            grade = grade(score),
            findings = findings.sortedBy { it.tier.ordinal },
            deductions = deductions.filter { it.points > 0 }.sortedByDescending { it.points },
            inputs = inputs,
        )
    }

    fun grade(score: Int): String = when {
        score >= 90 -> "A"
        score >= 75 -> "B"
        score >= 60 -> "C"
        score >= 40 -> "D"
        else -> "F"
    }

    /**
     * The report as Markdown, for a pull request or a team chat. Ends with
     * one attribution line, the only way a report can tell a reader where it
     * came from; it carries no tracking of any kind.
     */
    fun markdown(report: Report, projectName: String): String = buildString {
        appendLine("## Dependency checkup: $projectName")
        appendLine()
        appendLine("**Score ${report.score}/100 (${report.grade})** across ${report.inputs.dependencies} declared dependencies")
        for (tier in Tier.entries) {
            val inTier = report.findings.filter { it.tier == tier }
            if (inTier.isEmpty()) continue
            appendLine()
            appendLine("### ${tierTitle(tier)}")
            for (finding in inTier) {
                appendLine("- ${finding.title}")
                finding.detail?.let { appendLine("  - $it") }
            }
        }
        if (report.findings.isEmpty()) {
            appendLine()
            appendLine("Nothing to act on: no known vulnerabilities, no linkage problems, everything current.")
        }
        if (report.deductions.isNotEmpty()) {
            appendLine()
            appendLine("<details><summary>How the score was computed</summary>")
            appendLine()
            for (deduction in report.deductions) appendLine("- -${deduction.points}: ${deduction.reason}")
            appendLine()
            appendLine("</details>")
        }
        if (!report.inputs.ownCodeChecked) {
            appendLine()
            appendLine("_The project was not built, so calls from its own code were not checked._")
        }
        appendLine()
        append("_Checked with [Staleguard](https://plugins.jetbrains.com/plugin/33571-staleguard), locally in the IDE._")
    }

    fun tierTitle(tier: Tier): String = when (tier) {
        Tier.FIX_NOW -> "Fix now"
        Tier.FIX_SOON -> "Fix soon"
        Tier.REVIEW -> "Worth a look"
        Tier.ROUTINE -> "Routine"
    }

    private fun severityPoints(severity: String?): Int = when (severity?.uppercase()) {
        "CRITICAL" -> 25
        "HIGH" -> 15
        "MODERATE", "MEDIUM" -> 8
        "LOW" -> 4
        else -> 10 // unrated is not "low"
    }

    private fun severityRank(severity: String?): Int = when (severity?.uppercase()) {
        "CRITICAL" -> 4
        "HIGH" -> 3
        null -> 2
        "MODERATE", "MEDIUM" -> 1
        else -> 0
    }

    private fun reachOrder(reach: Reach): Int = when (reach) {
        Reach.REACHED -> 0
        Reach.UNDETERMINED, Reach.NOT_CHECKED -> 1
        Reach.TESTS_ONLY -> 2
        Reach.NOT_REACHED -> 3
    }

    private fun reachLabel(reach: Reach): String = when (reach) {
        Reach.REACHED -> "reached"
        Reach.TESTS_ONLY -> "tests only, half weight"
        Reach.NOT_REACHED -> "not reached, 40% weight"
        Reach.UNDETERMINED -> "undetermined, full weight"
        Reach.NOT_CHECKED -> "not checked, full weight"
    }

    private fun plural(count: Int, one: String, many: String) = if (count == 1) one else many
}
