package com.tampwell.staleguard.repository

/**
 * The network edge, kept behind an interface so the lookup engine is testable
 * without any HTTP. Implementations are blocking — callers run them on
 * Dispatchers.IO.
 */
interface MavenRepositoryClient {

    /**
     * Fetches a maven-metadata.xml, revalidating with [previousEtag] when
     * given (repo1.maven.org honors If-None-Match with 304s).
     */
    fun fetchMetadata(url: String, previousEtag: String?): FetchResult

    /** Last-Modified of a URL via HEAD, as epoch millis, or null if unavailable. */
    fun fetchLastModified(url: String): Long?
}

sealed interface FetchResult {
    /** Fresh body; [etag] to store for the next conditional request. */
    data class Fetched(val body: String, val etag: String?) : FetchResult

    /** Cached copy is still valid (HTTP 304). */
    data object NotModified : FetchResult

    /** Artifact does not exist in this repository (HTTP 404). */
    data object NotFound : FetchResult

    /** Transient failure — keep serving stale data. */
    data class Failed(val reason: String) : FetchResult
}
