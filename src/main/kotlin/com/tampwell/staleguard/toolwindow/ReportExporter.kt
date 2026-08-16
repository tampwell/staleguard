package com.tampwell.staleguard.toolwindow

/**
 * Serializes the statistics view for sharing outside the IDE — Markdown for
 * chat/PR descriptions, CSV for spreadsheets. Pure string logic.
 */
object ReportExporter {

    data class Row(
        val module: String,
        val coordinate: String,
        val currentVersion: String,
        val suggestedVersion: String,
        val severity: String,
        val license: String,
    )

    fun markdown(projectName: String, rows: List<Row>): String = buildString {
        appendLine("# Staleguard report — $projectName")
        appendLine()
        if (rows.isEmpty()) {
            appendLine("All dependencies are up to date.")
            return@buildString
        }
        appendLine("| Module | Dependency | Current | Suggested | Severity | License |")
        appendLine("|---|---|---|---|---|---|")
        for (row in rows) {
            appendLine(
                "| ${row.module.mdEscape()} | ${row.coordinate.mdEscape()} | ${row.currentVersion} " +
                    "| ${row.suggestedVersion} | ${row.severity} | ${row.license.mdEscape()} |",
            )
        }
    }

    fun csv(rows: List<Row>): String = buildString {
        appendLine("module,dependency,current,suggested,severity,license")
        for (row in rows) {
            appendLine(
                listOf(row.module, row.coordinate, row.currentVersion, row.suggestedVersion, row.severity, row.license)
                    .joinToString(",") { it.csvEscape() },
            )
        }
    }

    private fun String.mdEscape() = replace("|", "\\|")

    private fun String.csvEscape(): String =
        if (any { it == ',' || it == '"' || it == '\n' }) "\"${replace("\"", "\"\"")}\"" else this
}
