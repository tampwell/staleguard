package com.tampwell.staleguard.changelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoSlugTest {

    @Test
    fun `scm git url resolves to a github slug`() {
        val slug = RepoSlug.from("scm:git:https://github.com/qos-ch/slf4j.git")!!
        assertEquals(RepoSlug.Host.GITHUB, slug.host)
        assertEquals("qos-ch", slug.owner)
        assertEquals("slf4j", slug.name)
    }

    @Test
    fun `ssh style url resolves too`() {
        val slug = RepoSlug.from("git@github.com:FasterXML/jackson-databind.git")!!
        assertEquals("FasterXML", slug.owner)
        assertEquals("jackson-databind", slug.name)
    }

    @Test
    fun `gitlab urls are recognized`() {
        assertEquals(RepoSlug.Host.GITLAB, RepoSlug.from("https://gitlab.com/group/project")!!.host)
    }

    @Test
    fun `non-forge urls yield null`() {
        assertNull(RepoSlug.from("https://svn.apache.org/repos/asf/commons/"))
        assertNull(RepoSlug.from(null))
    }

    @Test
    fun `release api url is tag-addressed`() {
        val slug = RepoSlug(RepoSlug.Host.GITHUB, "qos-ch", "slf4j")
        assertEquals(
            "https://api.github.com/repos/qos-ch/slf4j/releases/tags/v2.0.18",
            slug.releaseByTagUrl("v2.0.18"),
        )
    }

    @Test
    fun `changelog raw candidates start with main CHANGELOG md`() {
        val urls = RepoSlug(RepoSlug.Host.GITHUB, "o", "r").changelogRawUrls()
        assertEquals("https://raw.githubusercontent.com/o/r/main/CHANGELOG.md", urls.first())
        assertTrue(urls.any { "master" in it })
    }
}
