package com.tampwell.staleguard.repository

import com.intellij.openapi.diagnostic.logger
import com.intellij.util.io.HttpRequests
import java.net.HttpURLConnection

/**
 * Real network implementation on the platform's HttpRequests, which honors the
 * IDE's proxy configuration and custom certificates automatically. Blocking by
 * design — always called from Dispatchers.IO by the engine.
 */
class HttpMavenRepositoryClient(pluginVersion: String) : MavenRepositoryClient {

    private val log = logger<HttpMavenRepositoryClient>()

    // Identifies us politely to Maven Central without embedding a personal
    // account handle — contact address only, stable across repo moves.
    private val userAgent = "Staleguard/$pluginVersion (IntelliJ plugin; staleguard@tampwell.com)"

    override fun fetchMetadata(url: String, previousEtag: String?): FetchResult =
        try {
            HttpRequests.request(url)
                .userAgent(userAgent)
                .connectTimeout(CONNECT_TIMEOUT_MS)
                .readTimeout(READ_TIMEOUT_MS)
                .throwStatusCodeException(false)
                .tuner { connection ->
                    previousEtag?.let { connection.setRequestProperty("If-None-Match", it) }
                }
                .connect { request ->
                    val connection = request.connection as HttpURLConnection
                    when (val code = connection.responseCode) {
                        HttpURLConnection.HTTP_OK ->
                            FetchResult.Fetched(request.readString(), connection.getHeaderField("ETag"))
                        HttpURLConnection.HTTP_NOT_MODIFIED -> FetchResult.NotModified
                        HttpURLConnection.HTTP_NOT_FOUND -> FetchResult.NotFound
                        else -> FetchResult.Failed("HTTP $code for $url")
                            .also { log.info("Staleguard: metadata fetch got HTTP $code for $url") }
                    }
                }
        } catch (e: Exception) {
            // Never silent: a broken network must be visible in idea.log.
            log.info("Staleguard: metadata fetch failed for $url: ${e.javaClass.simpleName}: ${e.message}")
            FetchResult.Failed("${e.javaClass.simpleName}: ${e.message}")
        }

    override fun fetchPomDetails(url: String): PomDetails? =
        try {
            HttpRequests.request(url)
                .userAgent(userAgent)
                .connectTimeout(CONNECT_TIMEOUT_MS)
                .readTimeout(READ_TIMEOUT_MS)
                .throwStatusCodeException(false)
                .connect { request ->
                    val connection = request.connection as HttpURLConnection
                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val lastModified = connection.lastModified.takeIf { it > 0 }
                        val info = runCatching { PomInfo.parse(request.readString()) }.getOrDefault(PomInfo.EMPTY)
                        PomDetails(lastModified, info)
                    } else {
                        null
                    }
                }
        } catch (e: Exception) {
            log.info("Staleguard: pom fetch failed for $url: ${e.javaClass.simpleName}: ${e.message}")
            null
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
    }
}
