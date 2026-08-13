package com.tampwell.staleguard.repository

import com.tampwell.staleguard.version.MavenVersion
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
}

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

    /**
     * Latest known versions for [coordinates], or null when the artifact is
     * unknown to the repository (404) or has never been fetchable.
     */
    suspend fun lookup(coordinates: Coordinates): ArtifactVersions? {
        val flight = inFlightLock.withLock {
            inFlight.getOrPut(coordinates) {
                scope.async { doLookup(coordinates) }
            }
        }
        try {
            return flight.await()
        } finally {
            inFlightLock.withLock { inFlight.remove(coordinates) }
        }
    }

    private suspend fun doLookup(coordinates: Coordinates): ArtifactVersions? {
        val cached = withContext(ioDispatcher) { cache.read(coordinates) }
        val now = clock()

        if (cached != null && now - cached.fetchedAtMillis < ttlMillis) {
            return cached.toResult(coordinates, stale = false)
        }

        return withContext(ioDispatcher) {
            val url = MavenRepositoryUrls.metadataUrl(repositoryBaseUrl, coordinates)
            when (val result = client.fetchMetadata(url, cached?.etag)) {
                is FetchResult.Fetched -> {
                    val metadata = try {
                        MavenMetadata.parse(result.body)
                    } catch (_: MetadataParseException) {
                        return@withContext cached?.toResult(coordinates, stale = true)
                    }
                    val entry = buildEntry(coordinates, metadata, result.etag, cached, now)
                    cache.write(coordinates, entry)
                    entry.toResult(coordinates, stale = false)
                }

                FetchResult.NotModified -> {
                    // Same content — refresh the clock, keep everything else.
                    val refreshed = cached?.copy(fetchedAtMillis = now) ?: return@withContext null
                    cache.write(coordinates, refreshed)
                    refreshed.toResult(coordinates, stale = false)
                }

                FetchResult.NotFound -> null

                is FetchResult.Failed -> cached?.toResult(coordinates, stale = true)
            }
        }
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
    }
}
