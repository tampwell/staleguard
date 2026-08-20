package com.tampwell.staleguard.security

import com.intellij.openapi.diagnostic.logger
import com.intellij.util.io.HttpRequests
import java.net.HttpURLConnection

sealed interface OsvFetch {
    data class Ok(val body: String) : OsvFetch
    data class Failed(val reason: String) : OsvFetch
}

interface OsvClient {
    /** Blocking POST to the OSV query api — always called from Dispatchers.IO by the engine. */
    fun query(packageName: String, version: String): OsvFetch

    /**
     * One POST answering "which of these have any advisories" for many
     * (packageName, version) pairs — results are position-matched id stubs,
     * so hits still need a per-key [query] for details.
     */
    fun queryBatch(queries: List<Pair<String, String>>): OsvFetch
}

/**
 * Real implementation on the platform's HttpRequests (proxy and certificate
 * aware, same as the version lookup client). One request per artifact+version;
 * the engine caches for a day, so a project costs one burst per day.
 */
class HttpOsvClient(pluginVersion: String) : OsvClient {

    private val log = logger<HttpOsvClient>()

    private val userAgent = "Staleguard/$pluginVersion (IntelliJ plugin; staleguard@tampwell.com)"

    override fun query(packageName: String, version: String): OsvFetch =
        post(QUERY_URL, singleQueryJson(packageName, version), "query for $packageName $version")

    override fun queryBatch(queries: List<Pair<String, String>>): OsvFetch =
        post(
            BATCH_URL,
            queries.joinToString(",", prefix = """{"queries":[""", postfix = "]}") { (name, version) ->
                singleQueryJson(name, version)
            },
            "batch of ${queries.size}",
        )

    // Field values come from build files, but escape anyway — a version
    // string with a quote must corrupt the request, not the JSON.
    private fun singleQueryJson(packageName: String, version: String): String =
        """{"package":{"name":${jsonString(packageName)},"ecosystem":"Maven"},"version":${jsonString(version)}}"""

    private fun post(url: String, payload: String, what: String): OsvFetch =
        try {
            HttpRequests.post(url, "application/json")
                .userAgent(userAgent)
                .connectTimeout(CONNECT_TIMEOUT_MS)
                .readTimeout(READ_TIMEOUT_MS)
                .throwStatusCodeException(false)
                .connect { request ->
                    request.write(payload)
                    val connection = request.connection as HttpURLConnection
                    when (val code = connection.responseCode) {
                        HttpURLConnection.HTTP_OK -> OsvFetch.Ok(request.readString())
                        else -> OsvFetch.Failed("HTTP $code")
                            .also { log.info("Staleguard: OSV $what got HTTP $code") }
                    }
                }
        } catch (e: Exception) {
            log.info("Staleguard: OSV $what failed: ${e.javaClass.simpleName}: ${e.message}")
            OsvFetch.Failed("${e.javaClass.simpleName}: ${e.message}")
        }

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        const val QUERY_URL = "https://api.osv.dev/v1/query"
        const val BATCH_URL = "https://api.osv.dev/v1/querybatch"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
    }
}
