package com.tampwell.staleguard.repository

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRouterTest {

    private val central = MavenLayoutSource(MavenRepositoryUrls.MAVEN_CENTRAL)
    private val google = GoogleMavenSource()
    private val portal = MavenLayoutSource(SourceRouter.PLUGIN_PORTAL_URL)

    private fun router(googleGroups: Set<String>? = null) =
        SourceRouter(central, google, portal, { googleGroups })

    // --- routing ---

    @Test
    fun `androidx routes google first with central fallback`() {
        val sources = router().sourcesFor(Coordinates("androidx.core", "core-ktx"))
        assertEquals(listOf(google, central), sources)
    }

    @Test
    fun `android arch legacy groups route to google`() {
        assertEquals(google, router().sourcesFor(Coordinates("android.arch.lifecycle", "runtime")).first())
    }

    @Test
    fun `androidxish name that is not a prefix match stays central`() {
        // "androidxtra" must not match the "androidx" prefix rule
        assertEquals(listOf(central), router().sourcesFor(Coordinates("androidxtra.thing", "lib")))
    }

    @Test
    fun `google-indexed group prefers central with google fallback`() {
        val sources = router(setOf("com.google.firebase"))
            .sourcesFor(Coordinates("com.google.firebase", "firebase-bom"))
        assertEquals(listOf(central, google), sources)
    }

    @Test
    fun `unknown group is central only`() {
        assertEquals(listOf(central), router(setOf("com.google.firebase")).sourcesFor(Coordinates("org.slf4j", "slf4j-api")))
    }

    @Test
    fun `plugin markers route to the plugin portal first`() {
        val sources = router().sourcesFor(Coordinates("org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin.jvm.gradle.plugin"))
        assertEquals(listOf(portal, central), sources)
    }

    @Test
    fun `master index unavailable degrades to central for ambiguous groups`() {
        assertEquals(listOf(central), router(null).sourcesFor(Coordinates("com.google.firebase", "firebase-bom")))
    }

    // --- google formats ---

    @Test
    fun `group index parsing finds the artifact's versions`() {
        val body = """<?xml version='1.0' encoding='UTF-8'?>
            <androidx.core>
              <core versions="1.0.0,1.1.0,1.19.0"/>
              <core-ktx versions="0.1, 0.2 ,1.19.0"/>
            </androidx.core>"""
        val versions = google.versionsIn(body, Coordinates("androidx.core", "core-ktx"))
        assertEquals(listOf("0.1", "0.2", "1.19.0"), versions)
    }

    @Test
    fun `group index without the artifact yields empty`() {
        val body = "<androidx.core><core versions=\"1.0.0\"/></androidx.core>"
        assertTrue(google.versionsIn(body, Coordinates("androidx.core", "absent")).isEmpty())
    }

    @Test
    fun `google urls are built against dl google com`() {
        assertEquals(
            "${GoogleMavenSource.BASE_URL}/androidx/core/group-index.xml",
            google.metadataUrl(Coordinates("androidx.core", "core")),
        )
    }

    // --- master index ---

    private class ScriptedClient(var onFetch: (String) -> FetchResult) : MavenRepositoryClient {
        val fetches = AtomicInteger()
        override fun fetchMetadata(url: String, previousEtag: String?): FetchResult {
            fetches.incrementAndGet()
            return onFetch(url)
        }

        override fun fetchPomDetails(url: String): PomDetails? = null
    }

    @Test
    fun `master index caches success for its ttl`() {
        val client = ScriptedClient { FetchResult.Fetched("<metadata><androidx.core/><com.google.firebase/></metadata>", null) }
        var now = 0L
        val index = GoogleMasterIndex(client) { now }

        assertEquals(setOf("androidx.core", "com.google.firebase"), index.groups())
        now = GoogleMasterIndex.SUCCESS_TTL_MILLIS - 1
        index.groups()
        assertEquals(1, client.fetches.get())

        now = GoogleMasterIndex.SUCCESS_TTL_MILLIS + 1
        index.groups()
        assertEquals(2, client.fetches.get())
    }

    @Test
    fun `master index failure backs off and serves nothing`() {
        val client = ScriptedClient { FetchResult.Failed("boom") }
        var now = 0L
        val index = GoogleMasterIndex(client) { now }

        assertNull(index.groups())
        now = GoogleMasterIndex.FAILURE_TTL_MILLIS - 1
        assertNull(index.groups())
        assertEquals(1, client.fetches.get())

        now = GoogleMasterIndex.FAILURE_TTL_MILLIS + 1
        index.groups()
        assertEquals(2, client.fetches.get())
    }

    // --- engine + chain ---

    @Test
    fun `central 404 falls through to google for an indexed group`() = runBlocking {
        val googleBody = "<com.google.firebase><firebase-bom versions=\"33.0.0,34.1.0\"/></com.google.firebase>"
        val client = ScriptedClient { url ->
            when {
                url.startsWith(MavenRepositoryUrls.MAVEN_CENTRAL) -> FetchResult.NotFound
                url.endsWith("/com/google/firebase/group-index.xml") -> FetchResult.Fetched(googleBody, null)
                else -> FetchResult.Failed("unexpected $url")
            }
        }
        val engine = VersionLookupEngine(
            scope = CoroutineScope(Dispatchers.Default),
            client = client,
            cache = DiskVersionCache(Files.createTempDirectory("staleguard-router-test")),
            ioDispatcher = Dispatchers.IO,
            clock = { 100L },
            router = SourceRouter(central, google, portal, { setOf("com.google.firebase") }),
        )

        val result = engine.lookup(Coordinates("com.google.firebase", "firebase-bom"))

        assertEquals("34.1.0", result?.latest?.value)
    }

    @Test
    fun `miss in every source is a terminal not-found`() = runBlocking {
        val client = ScriptedClient { FetchResult.NotFound }
        val engine = VersionLookupEngine(
            scope = CoroutineScope(Dispatchers.Default),
            client = client,
            cache = DiskVersionCache(Files.createTempDirectory("staleguard-router-test2")),
            ioDispatcher = Dispatchers.IO,
            clock = { 100L },
            router = SourceRouter(central, google, portal, { setOf("com.google.firebase") }),
        )
        val coords = Coordinates("com.google.firebase", "gone")

        assertNull(engine.lookup(coords))
        val peek = engine.peek(coords)
        assertNull(peek?.value)
        assertEquals(false, peek?.failed)
    }
}
