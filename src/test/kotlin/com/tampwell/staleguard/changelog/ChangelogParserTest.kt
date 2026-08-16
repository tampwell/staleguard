package com.tampwell.staleguard.changelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogParserTest {

    private val keepAChangelog = """
        # Changelog

        ## [2.0.0] - 2024-06-01
        ### Changed
        - Breaking change: renamed the Logger factory

        ## [1.9.0] - 2024-01-05
        ### Added
        - New appender API

        ## [1.8.0] - 2023-08-01
        - Bug fixes
    """.trimIndent()

    @Test
    fun `extracts exactly the named version's section`() {
        val section = ChangelogParser.sectionFor(keepAChangelog, "1.9.0")!!
        assertTrue(section.contains("New appender API"))
        assertFalse(section.contains("renamed the Logger factory"))
        assertFalse(section.contains("Bug fixes"))
    }

    @Test
    fun `handles plain and v-prefixed heading styles`() {
        val plain = "1.2.3 (2024-01-01)\n- fixed things\n\n1.2.2 (2023-12-01)\n- other"
        assertEquals("- fixed things", ChangelogParser.sectionFor(plain, "1.2.3"))

        val hashed = "## v3.1.0\nStuff here\n## v3.0.0\nOld stuff"
        assertEquals("Stuff here", ChangelogParser.sectionFor(hashed, "3.1.0"))
    }

    @Test
    fun `missing version yields null not a wrong section`() {
        assertNull(ChangelogParser.sectionFor(keepAChangelog, "1.5.0"))
    }

    @Test
    fun `version match does not treat dots as wildcards`() {
        // "1.9.0" must not match a hypothetical "119.0" heading
        assertFalse(ChangelogParser.isVersionHeading("## [119.0]", "1.9.0"))
    }

    @Test
    fun `breaking signals separate strong from moderate`() {
        val scan = BreakingSignals.scan("Breaking change: the Foo class was removed; Bar is deprecated.")
        assertTrue(scan.hasBreaking)
        assertTrue("removed" in scan.moderate)
        assertTrue("deprecated" in scan.moderate)
    }

    @Test
    fun `calm notes scan empty`() {
        assertTrue(BreakingSignals.scan("Added dark mode. Fixed a typo.").isEmpty)
    }
}
