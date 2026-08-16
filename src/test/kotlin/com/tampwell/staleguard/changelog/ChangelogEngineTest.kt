package com.tampwell.staleguard.changelog

import com.tampwell.staleguard.repository.FetchResult
import com.tampwell.staleguard.repository.MavenRepositoryClient
import com.tampwell.staleguard.repository.PomDetails
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogEngineTest {

    private class WebFake(val responses: Map<String, String>) : MavenRepositoryClient {
        val requests = AtomicInteger()
        override fun fetchMetadata(url: String, previousEtag: String?): FetchResult {
            requests.incrementAndGet()
            return responses[url]?.let { FetchResult.Fetched(it, null) } ?: FetchResult.NotFound
        }

        override fun fetchPomDetails(url: String): PomDetails? = null
    }

    private val scm = "scm:git:https://github.com/qos-ch/slf4j.git"
    private val changelogUrl = "https://raw.githubusercontent.com/qos-ch/slf4j/main/CHANGELOG.md"

    @Test
    fun `one changelog fetch covers the whole skipped range`() {
        val client = WebFake(
            mapOf(
                changelogUrl to """
                    ## [2.0.18]
                    Breaking change: removed legacy binding
                    ## [2.0.17]
                    Fixed things
                    ## [1.7.36]
                    Old release
                """.trimIndent(),
            ),
        )
        val engine = ChangelogEngine(client)

        val summary = engine.summarize(scm, "slf4j-api", "2.0.16", "2.0.18", listOf("2.0.16", "2.0.17", "2.0.18"))!!

        assertEquals(listOf("2.0.17", "2.0.18"), summary.notes.map { it.version })
        assertTrue(summary.signals.hasBreaking)
        assertTrue(summary.uncovered.isEmpty())
        assertEquals(1, client.requests.get())
    }

    @Test
    fun `falls back to release notes for the suggested version`() {
        val client = WebFake(
            mapOf(
                "https://api.github.com/repos/qos-ch/slf4j/releases/tags/v2.0.18" to
                    """{"tag_name":"v2.0.18","body":"Migration guide: see docs. Removed X."}""",
            ),
        )
        val engine = ChangelogEngine(client)

        val summary = engine.summarize(scm, "slf4j-api", "2.0.17", "2.0.18", listOf("2.0.17", "2.0.18"))!!

        assertEquals(1, summary.notes.size)
        assertTrue(summary.signals.hasBreaking)
    }

    @Test
    fun `request budget is a hard ceiling`() {
        val client = WebFake(emptyMap())
        val engine = ChangelogEngine(client)

        assertNull(engine.summarize(scm, "slf4j-api", "1.0", "2.0", listOf("1.0", "2.0")))
        assertTrue(
            "at most ${ChangelogEngine.MAX_REQUESTS} requests, saw ${client.requests.get()}",
            client.requests.get() <= ChangelogEngine.MAX_REQUESTS,
        )
    }

    @Test
    fun `no forge slug means no network at all`() {
        val client = WebFake(emptyMap())
        assertNull(ChangelogEngine(client).summarize("https://svn.example.org/x", "a", "1.0", "2.0", listOf("1.0", "2.0")))
        assertEquals(0, client.requests.get())
    }

    @Test
    fun `release body parses github and gitlab shapes`() {
        val engine = ChangelogEngine(WebFake(emptyMap()))
        assertEquals("hi", engine.releaseBody("""{"body":"hi"}"""))
        assertEquals("desc", engine.releaseBody("""{"description":"desc"}"""))
        assertNull(engine.releaseBody("not json"))
        assertNull(engine.releaseBody("""{"body":""}"""))
    }
}
