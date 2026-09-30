package com.tampwell.staleguard.checkup

import com.tampwell.staleguard.checkup.CheckupReport.Inputs
import com.tampwell.staleguard.checkup.CheckupReport.Reach
import com.tampwell.staleguard.checkup.CheckupReport.Tier
import com.tampwell.staleguard.checkup.CheckupReport.Vulnerability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckupReportTest {

    private fun vuln(reach: Reach, severity: String? = "CRITICAL", artifact: String = "log4j-core") =
        Vulnerability(artifact, "2.14.1", "CVE-2021-44228", severity, "2.15.0", declared = true, reach = reach)

    @Test
    fun `a clean project scores 100 with nothing to act on`() {
        val report = CheckupReport.build(Inputs(dependencies = 12))

        assertEquals(100, report.score)
        assertEquals("A", report.grade)
        assertTrue(report.findings.isEmpty())
        assertTrue(CheckupReport.markdown(report, "demo").contains("Nothing to act on"))
    }

    @Test
    fun `every point lost is itemized`() {
        val report = CheckupReport.build(
            Inputs(
                vulnerabilities = listOf(vuln(Reach.REACHED)),
                linkageFailures = listOf("jackson-databind calls JsonParser.streamReadConstraints, missing in jackson-core 2.13.0"),
                lockDrifts = 1,
                majorUpdates = 3,
            ),
        )

        assertEquals(100 - report.deductions.sumOf { it.points }, report.score)
        assertEquals(listOf(25, 15, 3, 3), report.deductions.map { it.points })
    }

    @Test
    fun `not knowing costs exactly as much as a reached vulnerability`() {
        val reached = CheckupReport.build(Inputs(vulnerabilities = listOf(vuln(Reach.REACHED)))).score
        val unknown = CheckupReport.build(Inputs(vulnerabilities = listOf(vuln(Reach.UNDETERMINED)))).score
        val unchecked = CheckupReport.build(Inputs(vulnerabilities = listOf(vuln(Reach.NOT_CHECKED)))).score

        assertEquals(reached, unknown)
        assertEquals(reached, unchecked)
    }

    @Test
    fun `not reached demotes a vulnerability but never dismisses it`() {
        val report = CheckupReport.build(Inputs(vulnerabilities = listOf(vuln(Reach.NOT_REACHED))))

        assertEquals(90, report.score) // 40% of 25
        assertEquals(Tier.REVIEW, report.findings.single().tier)
        assertTrue(report.findings.single().title.contains("no static path"))
    }

    @Test
    fun `findings lead with what is reached, then by severity`() {
        val report = CheckupReport.build(
            Inputs(
                vulnerabilities = listOf(
                    vuln(Reach.NOT_REACHED, "CRITICAL", "a"),
                    vuln(Reach.REACHED, "LOW", "b"),
                    vuln(Reach.REACHED, "HIGH", "c"),
                ),
                patchUpdates = 7,
            ),
        )

        assertEquals(listOf("c", "b", "a"), report.findings.filter { it.title.contains("CVE") }.map { it.title.substringBefore(' ') })
        assertEquals(Tier.ROUTINE, report.findings.last().tier)
    }

    @Test
    fun `an unrated vulnerability is not treated as low`() {
        val unrated = CheckupReport.build(Inputs(vulnerabilities = listOf(vuln(Reach.REACHED, severity = null)))).score
        val low = CheckupReport.build(Inputs(vulnerabilities = listOf(vuln(Reach.REACHED, severity = "LOW")))).score

        assertTrue(unrated < low)
    }

    @Test
    fun `caps keep one category from drowning the rest and the score never goes negative`() {
        val report = CheckupReport.build(
            Inputs(
                vulnerabilities = List(10) { vuln(Reach.REACHED, artifact = "lib$it") },
                linkageFailures = List(10) { "failure $it" },
            ),
        )

        assertEquals(0, report.score)
        assertEquals("F", report.grade)
        assertEquals(45, report.deductions.single { it.reason.contains("fail at runtime") }.points)
    }

    @Test
    fun `markdown names the tiers, explains the score, and says when code was not checked`() {
        val markdown = CheckupReport.markdown(
            CheckupReport.build(
                Inputs(vulnerabilities = listOf(vuln(Reach.REACHED).copy(detail = "App.log -> JndiLookup.lookup")), ownCodeChecked = false),
            ),
            "demo",
        )

        assertTrue(markdown.contains("## Dependency checkup: demo"))
        assertTrue(markdown.contains("### Fix now"))
        assertTrue(markdown.contains("App.log -> JndiLookup.lookup"))
        assertTrue(markdown.contains("How the score was computed"))
        assertTrue(markdown.contains("was not built"))
        assertFalse(markdown.contains("—")) // plain punctuation only
    }
}
