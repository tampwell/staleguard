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
        try {
            // Field values come from build files, but escape anyway — a version
            // string with a quote must corrupt the request, not the JSON.
            val payload = """{"package":{"name":${jsonString(packageName)},"ecosystem":"Maven"},"version":${jsonString(version)}}"""
            HttpRequests.post(QUERY_URL, "application/json")
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
                            .also { log.info("Staleguard: OSV query got HTTP $code for $packageName $version") }
                    }
                }
        } catch (e: Exception) {
            log.info("Staleguard: OSV query failed for $packageName $version: ${e.javaClass.simpleName}: ${e.message}")
            OsvFetch.Failed("${e.javaClass.simpleName}: ${e.message}")
        }

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        const val QUERY_URL = "https://api.osv.dev/v1/query"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
    }
}
