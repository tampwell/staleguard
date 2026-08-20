package com.tampwell.staleguard.toolwindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportExporterTest {

    private val row = ReportExporter.Row(
        module = "app",
        coordinate = "org.slf4j:slf4j-api",
        currentVersion = "1.7.32",
        suggestedVersion = "2.0.18",
        severity = "Major",
        license = "MIT",
    )

    @Test
    fun `markdown has a table row per entry`() {
        val md = ReportExporter.markdown("demo", listOf(row))
        assertTrue(md.contains("# Staleguard report — demo"))
        assertTrue(md.contains("| app | org.slf4j:slf4j-api | 1.7.32 | 2.0.18 | Major | MIT |  |"))
    }

    @Test
    fun `advisories appear in their own column`() {
        val vulnerable = row.copy(advisories = "CVE-2021-45046 CVE-2021-44228")
        assertTrue(
            ReportExporter.markdown("demo", listOf(vulnerable))
                .contains("| MIT | CVE-2021-45046 CVE-2021-44228 |"),
        )
        assertTrue(ReportExporter.csv(listOf(vulnerable)).contains(",CVE-2021-45046 CVE-2021-44228"))
    }

    @Test
    fun `markdown pipes are escaped`() {
        val md = ReportExporter.markdown("demo", listOf(row.copy(license = "MIT|X11")))
        assertTrue(md.contains("MIT\\|X11"))
    }

    @Test
    fun `empty report says so instead of an empty table`() {
        assertTrue(ReportExporter.markdown("demo", emptyList()).contains("up to date"))
    }

    @Test
    fun `csv quotes fields containing commas and doubles quotes`() {
        val csv = ReportExporter.csv(listOf(row.copy(license = "Apache, \"2.0\"")))
        assertEquals(
            "module,dependency,current,suggested,severity,license,advisories\n" +
                "app,org.slf4j:slf4j-api,1.7.32,2.0.18,Major,\"Apache, \"\"2.0\"\"\",\n",
            csv.replace("\r\n", "\n"),
        )
    }
}
