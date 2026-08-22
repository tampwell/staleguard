package com.tampwell.staleguard.toolwindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportIssueUrlTest {

    @Test
    fun `targets the bug form and prefills the two fields users should not have to look up`() {
        val url = ReportIssueAction.issueUrl("IntelliJ IDEA 2025.2.1 (build IC-252.23892.409)", "1.5.0")
        assertTrue(url.startsWith("https://github.com/tampwell/staleguard/issues/new?template=bug_report.yml"))
        assertTrue(url.contains("&plugin-version=1.5.0"))
        assertTrue("field ids must match .github/ISSUE_TEMPLATE/bug_report.yml", url.contains("&ide="))
    }

    @Test
    fun `spaces and parentheses survive encoding`() {
        val url = ReportIssueAction.issueUrl("Android Studio Ladybug (build AI-241.1)", "1.5.0")
        assertFalse("a raw space would truncate the query", url.contains(" "))
        assertTrue(url.contains("Android+Studio+Ladybug"))
    }

    @Test
    fun `an unknown plugin version still produces a usable link`() {
        val url = ReportIssueAction.issueUrl("IntelliJ IDEA", "unknown")
        assertEquals(1, url.count { it == '?' })
        assertTrue(url.endsWith("plugin-version=unknown"))
    }
}
