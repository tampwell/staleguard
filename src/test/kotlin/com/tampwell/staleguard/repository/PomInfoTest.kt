package com.tampwell.staleguard.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PomInfoTest {

    @Test
    fun `parses licenses scm and description`() {
        val info = PomInfo.parse(
            """<project>
                <description>A JSON library</description>
                <licenses>
                    <license><name>Apache-2.0</name></license>
                    <license><name>MIT</name></license>
                </licenses>
                <scm><url>https://github.com/google/gson</url></scm>
            </project>""",
        )
        assertEquals(listOf("Apache-2.0", "MIT"), info.licenses)
        assertEquals("https://github.com/google/gson", info.scmUrl)
        assertEquals("A JSON library", info.description)
    }

    @Test
    fun `scm connection is fallback when url missing`() {
        val info = PomInfo.parse(
            "<project><scm><connection>scm:git:https://github.com/x/y.git</connection></scm></project>",
        )
        assertEquals("scm:git:https://github.com/x/y.git", info.scmUrl)
    }

    @Test
    fun `project url is last-resort fallback`() {
        val info = PomInfo.parse("<project><url>https://example.org</url></project>")
        assertEquals("https://example.org", info.scmUrl)
    }

    @Test
    fun `missing sections yield empty info`() {
        val info = PomInfo.parse("<project><artifactId>x</artifactId></project>")
        assertTrue(info.licenses.isEmpty())
        assertNull(info.scmUrl)
        assertNull(info.description)
    }

    @Test
    fun `malformed xml yields EMPTY not exception`() {
        assertEquals(PomInfo.EMPTY, PomInfo.parse("<project><licenses>"))
    }

    @Test
    fun `doctype is rejected - XXE hardening`() {
        val evil = """<?xml version="1.0"?><!DOCTYPE p [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <project><description>&x;</description></project>"""
        assertEquals(PomInfo.EMPTY, PomInfo.parse(evil))
    }

    @Test
    fun `copyleft detection catches GPL family and not permissive licenses`() {
        assertTrue(PomInfo.isCopyleft("GNU General Public License v3.0"))
        assertTrue(PomInfo.isCopyleft("LGPL-2.1"))
        assertTrue(PomInfo.isCopyleft("AGPL-3.0"))
        assertTrue(PomInfo.isCopyleft("Server Side Public License (SSPL)"))
        assertEquals(false, PomInfo.isCopyleft("Apache-2.0"))
        assertEquals(false, PomInfo.isCopyleft("MIT"))
        assertEquals(false, PomInfo.isCopyleft("Eclipse Public License 2.0"))
    }
}
