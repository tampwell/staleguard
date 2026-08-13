package com.tampwell.staleguard.repository

import com.tampwell.staleguard.version.MavenVersion
import com.tampwell.staleguard.version.isStable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Everything the UI layer needs to know about one artifact's freshness. */
data class ArtifactVersions(
    val coordinates: Coordinates,
    val versions: List<MavenVersion>,
    /** Epoch millis of the newest release's deploy date; null if undetermined. */
    val newestReleaseAtMillis: Long?,
    /** True when this result came from cache older than the TTL (network was down). */
    val stale: Boolean,
) {
    val latest: MavenVersion? get() = versions.maxOrNull()

    /** Newest stable version — what Staleguard suggests by default. */
    val latestStable: MavenVersion? get() = versions.filter { it.isStable }.maxOrNull()
}

/**
 * A synchronous snapshot read.
 *
 * [failed] distinguishes the two null-ish cases callers MUST treat
 * differently: value == null && !failed means the repository is known NOT to
 * have this artifact (404) — never re-enqueue. failed == true means the last
 * fetch errored — callers should keep requesting lookups (the engine
 * throttles real retries to every few minutes).
 */
data class PeekResult(
    val value: ArtifactVersions?,
    val fetchedAtMillis: Long,
    val failed: Boolean = false,
)

/**
 * Orchestrates cache + network for version lookups. Platform-free by design:
 * the client, cache directory, clock, and IO dispatcher are all injected, so
 * every policy below is unit-tested without an IDE or a network:
 *
 *  - TTL: within [ttlMillis], serve from cache with zero I/O.
 *  - Expired: conditional refetch (ETag); 304 refreshes the timestamp cheaply.
 *  - Failure: serve stale data forever rather than nothing (marked [ArtifactVersions.stale]).
 *  - Coalescing: concurrent lookups of the same coordinates share one flight.
 *  - Abandonment date: one HEAD for the newest version's .pom, cached until
 *    the newest version changes (release dates are immutable).
 */
class VersionLookupEngine(
    private val scope: CoroutineScope,
    private val client: MavenRepositoryClient,
    private val cache: DiskVersionCache,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val repositoryBaseUrl: String = MavenRepositoryUrls.MAVEN_CENTRAL,
) {

    private val inFlight = mutableMapOf<Coordinates, Deferred<ArtifactVersions?>>()
    private val inFlightLock = Mutex()

    /** Warm snapshot of completed lookups. THE only thing peek() touches. */
    private val memory = java.util.concurrent.ConcurrentHashMap<Coordinates, PeekResult>()

    /**
     * Synchronous, I/O-free read of the warm cache. Safe from any thread,
     * including highlighting passes. Null = never looked up this session.
     */
    fun peek(coordinates: Coordinates): PeekResult? = memory[coordinates]

    /**
     * Latest known versions for [coordinates], or null when the artifact is
     * unknown to the repository (404) or has never been fetchable.
     *
     * [force] bypasses the TTL (a conditional request still happens, so an
     * unchanged artifact costs only a 304) — used by explicit "Refresh".
     */
    suspend fun lookup(coordinates: Coordinates, force: Boolean = false): ArtifactVersions? {
        val flight = inFlightLock.withLock {
            inFlight.getOrPut(coordinates) {
                scope.async { doLookup(coordinates, force) }
            }
        }
        try {
            return flight.await()
        } finally {
            inFlightLock.withLock { inFlight.remove(coordinates) }
        }
    }

    private suspend fun doLookup(coordinates: Coordinates, force: Boolean = false): ArtifactVersions? {
        // Memory fast path: repeat lookups within the TTL cost one map read.
        if (!force) {
            memory[coordinates]?.let { snapshot ->
                if (clock() - snapshot.fetchedAtMillis < ttlMillis) return snapshot.value
            }
        }

        val cached = withContext(ioDispatcher) { cache.read(coordinates) }
        val now = clock()

        if (!force && cached != null && now - cached.fetchedAtMillis < ttlMillis) {
            return cached.toResult(coordinates, stale = false)
                .also { memory[coordinates] = PeekResult(it, now) }
        }

        return withContext(ioDispatcher) {
            val url = MavenRepositoryUrls.metadataUrl(repositoryBaseUrl, coordinates)
            when (val result = client.fetchMetadata(url, cached?.etag)) {
                is FetchResult.Fetched -> {
                    val metadata = try {
                        MavenMetadata.parse(result.body)
                    } catch (_: MetadataParseException) {
                        return@withContext staleFallback(coordinates, cached)
                    }
                    val entry = buildEntry(coordinates, metadata, result.etag, cached, now)
                    cache.write(coordinates, entry)
                    entry.toResult(coordinates, stale = false)
                        .also { memory[coordinates] = PeekResult(it, now) }
                }

                FetchResult.NotModified -> {
                    // Same content — refresh the clock, keep everything else.
                    val refreshed = cached?.copy(fetchedAtMillis = now) ?: return@withContext null
                    cache.write(coordinates, refreshed)
                    refreshed.toResult(coordinates, stale = false)
                        .also { memory[coordinates] = PeekResult(it, now) }
                }

                // 404s are stable: remember for a full TTL so nothing re-enqueues them.
                FetchResult.NotFound -> {
                    memory[coordinates] = PeekResult(null, now)
                    null
                }

                is FetchResult.Failed -> staleFallback(coordinates, cached)
            }
        }
    }

    /**
     * Serve stale data after a failure, marked failed=true so peek() callers
     * know to keep asking, and timestamped so the memory fast path performs a
     * real retry only every [FAILURE_RETRY_MILLIS] instead of either
     * hammering the network every pass or going quiet for a full TTL.
     */
    private fun staleFallback(coordinates: Coordinates, cached: CachedArtifact?): ArtifactVersions? {
        val result = cached?.toResult(coordinates, stale = true)
        memory[coordinates] = PeekResult(result, clock() - ttlMillis + FAILURE_RETRY_MILLIS, failed = true)
        return result
    }

    private fun buildEntry(
        coordinates: Coordinates,
        metadata: MavenMetadata,
        etag: String?,
        previous: CachedArtifact?,
        now: Long,
    ): CachedArtifact {
        val newest = metadata.latest?.value
        // Release dates are immutable: refetch only when the newest version changed.
        val newestReleaseAt = if (newest != null && newest == previous?.newestReleaseVersion) {
            previous.newestReleaseAtMillis
        } else if (newest != null) {
            client.fetchLastModified(MavenRepositoryUrls.pomUrl(repositoryBaseUrl, coordinates, newest))
        } else {
            null
        }
        return CachedArtifact(
            groupId = coordinates.groupId,
            artifactId = coordinates.artifactId,
            versions = metadata.versions.map { it.value },
            etag = etag,
            fetchedAtMillis = now,
            newestReleaseAtMillis = newestReleaseAt,
            newestReleaseVersion = newest,
        )
    }

    private fun CachedArtifact.toResult(coordinates: Coordinates, stale: Boolean) = ArtifactVersions(
        coordinates = coordinates,
        versions = versions.map(::MavenVersion),
        newestReleaseAtMillis = newestReleaseAtMillis,
        stale = stale,
    )

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 24 * 60 * 60 * 1000 // 24h — decided 2026-08-13
        const val FAILURE_RETRY_MILLIS: Long = 5 * 60 * 1000 // retry failed fetches after 5min
    }
}
