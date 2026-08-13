package com.tampwell.staleguard.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScmUrlsTest {

    @Test
    fun `scm git https github with dot-git normalizes`() {
        assertEquals("https://github.com/google/gson", ScmUrls.webUrl("scm:git:https://github.com/google/gson.git"))
    }

    @Test
    fun `ssh github format normalizes`() {
        assertEquals("https://github.com/qos-ch/slf4j", ScmUrls.webUrl("git@github.com:qos-ch/slf4j.git"))
    }

    @Test
    fun `plain https github passes through`() {
        assertEquals("https://github.com/FasterXML/jackson", ScmUrls.webUrl("https://github.com/FasterXML/jackson"))
    }

    @Test
    fun `gitlab is recognized`() {
        assertEquals("https://gitlab.com/org/proj", ScmUrls.webUrl("scm:git:https://gitlab.com/org/proj.git"))
    }

    @Test
    fun `non-git https url passes through - Apache style`() {
        assertEquals(
            "https://svn.apache.org/viewvc/commons/proper/collections",
            ScmUrls.webUrl("scm:svn:https://svn.apache.org/viewvc/commons/proper/collections"),
        )
    }

    @Test
    fun `garbage yields null`() {
        assertNull(ScmUrls.webUrl(null))
        assertNull(ScmUrls.webUrl(""))
        assertNull(ScmUrls.webUrl("scm:git:git://weird.internal:path"))
    }

    @Test
    fun `github changelog goes to releases`() {
        assertEquals(
            "https://github.com/google/gson/releases",
            ScmUrls.changelogUrl("scm:git:https://github.com/google/gson.git"),
        )
    }

    @Test
    fun `gitlab changelog goes to dash-releases`() {
        assertEquals(
            "https://gitlab.com/org/proj/-/releases",
            ScmUrls.changelogUrl("https://gitlab.com/org/proj"),
        )
    }

    @Test
    fun `non-forge changelog is the web url itself`() {
        assertEquals("https://example.org", ScmUrls.changelogUrl("https://example.org"))
    }
}
