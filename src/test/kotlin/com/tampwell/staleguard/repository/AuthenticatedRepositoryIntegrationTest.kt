package com.tampwell.staleguard.repository

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end proof that per-host credentials work at the protocol level: a
 * real HTTP server requires Basic auth, and the REAL production client (the
 * platform HttpRequests path, proxy and certificate aware) talks to it. This
 * is the permanent, headless version of "live verify against a Nexus" — the
 * server behaves exactly like a private repository's metadata endpoint.
 */
class AuthenticatedRepositoryIntegrationTest : BasePlatformTestCase() {

    private lateinit var server: HttpServer
    private var port = 0
    private val lastAuthHeader = AtomicReference<String?>()
    private val lastUserAgent = AtomicReference<String?>()

    private val expectedAuth = RepositoryCredentials.basicAuthValue("builder", "s3cret".toCharArray())

    private val metadataXml = """
        <metadata>
          <groupId>com.corp</groupId>
          <artifactId>internal-lib</artifactId>
          <versioning>
            <latest>2.1.0</latest>
            <versions><version>1.0.0</version><version>2.1.0</version></versions>
          </versioning>
        </metadata>
    """.trimIndent()

    override fun setUp() {
        super.setUp()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port

        // Auth-gated metadata, like a private Nexus repository.
        server.createContext("/private/maven-metadata.xml") { exchange ->
            lastAuthHeader.set(exchange.requestHeaders.getFirst("Authorization"))
            lastUserAgent.set(exchange.requestHeaders.getFirst("User-Agent"))
            if (exchange.requestHeaders.getFirst("Authorization") == expectedAuth) {
                val body = metadataXml.toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } else {
                exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"corp\"")
                exchange.sendResponseHeaders(401, -1)
            }
            exchange.close()
        }

        // Anonymous endpoint: must receive NO Authorization header at all.
        server.createContext("/public/maven-metadata.xml") { exchange ->
            lastAuthHeader.set(exchange.requestHeaders.getFirst("Authorization"))
            val body = metadataXml.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }

        server.start()
    }

    override fun tearDown() {
        try {
            server.stop(0)
        } finally {
            super.tearDown()
        }
    }

    private fun client(authorized: Boolean) = HttpMavenRepositoryClient("test") { url ->
        if (authorized && url.contains("/private/")) expectedAuth else null
    }

    fun testAuthorizedFetchSucceedsWithExactHeader() {
        val result = client(authorized = true)
            .fetchMetadata("http://127.0.0.1:$port/private/maven-metadata.xml", previousEtag = null)
        assertTrue("expected Fetched, got $result", result is FetchResult.Fetched)
        assertTrue((result as FetchResult.Fetched).body.contains("<latest>2.1.0</latest>"))
        assertEquals(expectedAuth, lastAuthHeader.get())
        assertTrue("polite user agent expected", lastUserAgent.get().orEmpty().startsWith("Staleguard/"))
    }

    fun testAnonymousClientGetsCleanFailureFromAuthGate() {
        val result = client(authorized = false)
            .fetchMetadata("http://127.0.0.1:$port/private/maven-metadata.xml", previousEtag = null)
        assertTrue("expected Failed, got $result", result is FetchResult.Failed)
        assertTrue((result as FetchResult.Failed).reason.contains("401"))
        assertNull("no credentials must be sent when the lambda declines", lastAuthHeader.get())
    }

    fun testWrongCredentialsAreRejectedNotRetriedWithSecrets() {
        val wrongAuth = RepositoryCredentials.basicAuthValue("builder", "wrong".toCharArray())
        val result = HttpMavenRepositoryClient("test") { wrongAuth }
            .fetchMetadata("http://127.0.0.1:$port/private/maven-metadata.xml", previousEtag = null)
        assertTrue(result is FetchResult.Failed)
        assertTrue((result as FetchResult.Failed).reason.contains("401"))
    }

    fun testAnonymousEndpointReceivesNoAuthorizationHeader() {
        val result = client(authorized = true)
            .fetchMetadata("http://127.0.0.1:$port/public/maven-metadata.xml", previousEtag = null)
        assertTrue(result is FetchResult.Fetched)
        assertNull("credentials must never leak to unconfigured endpoints", lastAuthHeader.get())
    }
}
