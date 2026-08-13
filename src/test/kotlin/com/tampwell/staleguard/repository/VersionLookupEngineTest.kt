package com.tampwell.staleguard.repository

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionLookupEngineTest {

    private val coords = Coordinates("com.example", "demo")

    private fun metadataXml(versions: List<String>) = buildString {
        append("<metadata><groupId>com.example</groupId><artifactId>demo</artifactId><versioning><versions>")
        versions.forEach { append("<version>$it</version>") }
        append("</versions><lastUpdated>20260810120000</lastUpdated></versioning></metadata>")
    }

    private class FakeClient(
        var onFetch: (String, String?) -> FetchResult,
        var lastModified: Long? = 1_700_000_000_000L,
    ) : MavenRepositoryClient {
        val fetches = AtomicInteger()
        val headRequests = AtomicInteger()

        override fun fetchMetadata(url: String, previousEtag: String?): FetchResult {
            fetches.incrementAndGet()
            return onFetch(url, previousEtag)
        }

        override fun fetchPomDetails(url: String): PomDetails? {
            headRequests.incrementAndGet()
            return PomDetails(lastModified, PomInfo(licenses = listOf("Apache-2.0"), scmUrl = "https://github.com/x/y"))
        }
    }

    private fun newEngine(
        client: FakeClient,
        clock: () -> Long,
        ttl: Long = 1_000L,
    ): Pair<VersionLookupEngine, DiskVersionCache> {
        val cache = DiskVersionCache(Files.createTempDirectory("staleguard-engine-test"))
        return VersionLookupEngine(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default),
            client = client,
            cache = cache,
            ioDispatcher = Dispatchers.IO,
            clock = clock,
            ttlMillis = ttl,
        ) to cache
    }

    @Test
    fun `first lookup fetches and caches`() = runBlocking {
        val client = FakeClient({ _, _ -> FetchResult.Fetched(metadataXml(listOf("1.0", "2.0")), "\"e1\"") })
        val (engine, cache) = newEngine(client, clock = { 100L })

        val result = engine.lookup(coords)

        assertEquals("2.0", result?.latest?.value)
        assertFalse(result!!.stale)
        assertEquals(1, client.fetches.get())
        assertEquals(1, client.headRequests.get()) // newest-release date resolved once
        assertEquals("\"e1\"", cache.read(coords)?.etag)
        assertEquals(1_700_000_000_000L, result.newestReleaseAtMillis)
    }

    @Test
    fun `lookup within TTL is served from cache with zero network`() = runBlocking {
        val client = FakeClient({ _, _ -> FetchResult.Fetched(metadataXml(listOf("1.0")), null) })
        var now = 100L
        val (engine, _) = newEngine(client, clock = { now }, ttl = 1_000L)

        engine.lookup(coords)
        now = 900L // still inside TTL
        engine.lookup(coords)

        assertEquals(1, client.fetches.get())
    }

    @Test
    fun `expired cache revalidates with etag and 304 refreshes timestamp`() = runBlocking {
        var etagSeen: String? = null
        val client = FakeClient({ _, etag ->
            if (etag == null) {
                FetchResult.Fetched(metadataXml(listOf("1.0")), "\"e1\"")
            } else {
                etagSeen = etag
                FetchResult.NotModified
            }
        })
        var now = 100L
        val (engine, cache) = newEngine(client, clock = { now }, ttl = 1_000L)

        engine.lookup(coords)
        now = 5_000L // expired
        val second = engine.lookup(coords)

        assertEquals("\"e1\"", etagSeen)
        assertFalse(second!!.stale)
        assertEquals(2, client.fetches.get())
        assertEquals(5_000L, cache.read(coords)?.fetchedAtMillis) // 304 refreshed the clock
        assertEquals(1, client.headRequests.get()) // release date NOT refetched on 304
    }

    @Test
    fun `network failure serves stale cache`() = runBlocking {
        var fail = false
        val client = FakeClient({ _, _ ->
            if (fail) FetchResult.Failed("boom") else FetchResult.Fetched(metadataXml(listOf("1.0")), null)
        })
        var now = 100L
        val (engine, _) = newEngine(client, clock = { now }, ttl = 1_000L)

        engine.lookup(coords)
        now = 10_000L
        fail = true
        val result = engine.lookup(coords)

        assertEquals("1.0", result?.latest?.value)
        assertTrue(result!!.stale)
    }

    @Test
    fun `network failure with no cache returns null`() = runBlocking {
        val client = FakeClient({ _, _ -> FetchResult.Failed("offline") })
        val (engine, _) = newEngine(client, clock = { 100L })
        assertNull(engine.lookup(coords))
    }

    @Test
    fun `404 returns null and is not cached as data`() = runBlocking {
        val client = FakeClient({ _, _ -> FetchResult.NotFound })
        val (engine, cache) = newEngine(client, clock = { 100L })
        assertNull(engine.lookup(coords))
        assertNull(cache.read(coords))
    }

    @Test
    fun `concurrent lookups of same coordinates share one network flight`() = runBlocking {
        val client = FakeClient({ _, _ ->
            Thread.sleep(50) // make the flight slow enough to overlap
            FetchResult.Fetched(metadataXml(listOf("1.0")), null)
        })
        val (engine, _) = newEngine(client, clock = { 100L })

        coroutineScope {
            (1..8).map { async { engine.lookup(coords) } }.awaitAll()
        }

        assertEquals(1, client.fetches.get())
    }

    @Test
    fun `release date refetched only when newest version changes`() = runBlocking {
        var versions = listOf("1.0")
        val client = FakeClient({ _, _ -> FetchResult.Fetched(metadataXml(versions), null) })
        var now = 100L
        val (engine, _) = newEngine(client, clock = { now }, ttl = 1_000L)

        engine.lookup(coords)
        assertEquals(1, client.headRequests.get())

        now = 5_000L // expired; same newest version → no new HEAD
        engine.lookup(coords)
        assertEquals(1, client.headRequests.get())

        now = 10_000L
        versions = listOf("1.0", "2.0") // newest changed → one new HEAD
        engine.lookup(coords)
        assertEquals(2, client.headRequests.get())
    }

    @Test
    fun `peek is null before any lookup and populated after`() = runBlocking {
        val client = FakeClient({ _, _ -> FetchResult.Fetched(metadataXml(listOf("1.0", "2.0")), null) })
        val (engine, _) = newEngine(client, clock = { 100L })

        assertNull(engine.peek(coords))
        engine.lookup(coords)
        assertEquals("2.0", engine.peek(coords)?.value?.latest?.value)
    }

    @Test
    fun `peek remembers 404 as known-absent so callers stop re-enqueueing`() = runBlocking {
        val client = FakeClient({ _, _ -> FetchResult.NotFound })
        val (engine, _) = newEngine(client, clock = { 100L })

        engine.lookup(coords)
        val peeked = engine.peek(coords)
        assertTrue(peeked != null && peeked.value == null)
    }

    @Test
    fun `memory fast path serves repeat lookups without touching disk or network`() = runBlocking {
        val client = FakeClient({ _, _ -> FetchResult.Fetched(metadataXml(listOf("1.0")), null) })
        var now = 100L
        val (engine, cacheRef) = newEngine(client, clock = { now }, ttl = 1_000L)

        engine.lookup(coords)
        // Sabotage the disk cache: if the fast path works, this is never noticed.
        cacheRef.write(coords, cacheRef.read(coords)!!.copy(versions = listOf("sabotaged")))
        now = 900L
        val second = engine.lookup(coords)

        assertEquals("1.0", second?.latest?.value)
        assertEquals(1, client.fetches.get())
    }

    @Test
    fun `latestStable filters prereleases in lookup results`() = runBlocking {
        val client = FakeClient({ _, _ -> FetchResult.Fetched(metadataXml(listOf("1.0", "2.0-rc1")), null) })
        val (engine, _) = newEngine(client, clock = { 100L })

        val result = engine.lookup(coords)

        assertEquals("2.0-rc1", result?.latest?.value)
        assertEquals("1.0", result?.latestStable?.value)
    }

    @Test
    fun `malformed metadata body falls back to stale cache`() = runBlocking {
        var body = metadataXml(listOf("1.0"))
        val client = FakeClient({ _, _ -> FetchResult.Fetched(body, null) })
        var now = 100L
        val (engine, _) = newEngine(client, clock = { now }, ttl = 1_000L)

        engine.lookup(coords)
        now = 5_000L
        body = "<metadata><versioning>" // truncated garbage
        val result = engine.lookup(coords)

        assertEquals("1.0", result?.latest?.value)
        assertTrue(result!!.stale)
    }
}
