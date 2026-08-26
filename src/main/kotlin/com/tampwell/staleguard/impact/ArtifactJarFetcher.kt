package com.tampwell.staleguard.impact

import com.intellij.openapi.diagnostic.logger
import com.intellij.util.io.HttpRequests
import java.io.InputStream
import java.net.HttpURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/**
 * Downloads the binary for one version so its API surface can be read. Kept
 * behind an interface for the same reason [com.tampwell.staleguard.repository.MavenRepositoryClient]
 * is: the analysis is testable with no network.
 */
interface ArtifactJarFetcher {
    /**
     * Fetches the artifact whose .pom lives at [pomUrl] and returns a local
     * jar, or null when the repository does not have it. The caller owns the
     * returned file and deletes it.
     */
    fun fetch(pomUrl: String, destination: Path, cancelled: () -> Boolean): Path?
}

/**
 * Real implementation on the platform's HttpRequests, so proxies and custom
 * certificates are honored exactly as they are for metadata lookups.
 *
 * Android artifacts publish .aar rather than .jar; the classes live in a
 * nested classes.jar. Without that fallback the feature would be silently
 * useless for every androidx dependency, which is most of an Android module.
 */
class HttpArtifactJarFetcher(
    pluginVersion: String,
    private val authorizationFor: (url: String) -> String? = { null },
) : ArtifactJarFetcher {

    private val log = logger<HttpArtifactJarFetcher>()
    private val userAgent = "Staleguard/$pluginVersion (IntelliJ plugin; staleguard@tampwell.com)"

    override fun fetch(pomUrl: String, destination: Path, cancelled: () -> Boolean): Path? {
        download(MavenArtifactUrls.siblingWithExtension(pomUrl, "jar"), destination, cancelled)?.let { return it }
        val aar = destination.resolveSibling(destination.fileName.toString() + ".aar")
        try {
            download(MavenArtifactUrls.siblingWithExtension(pomUrl, "aar"), aar, cancelled) ?: return null
            return extractClassesJar(aar, destination)
        } finally {
            runCatching { Files.deleteIfExists(aar) }
        }
    }

    private fun download(url: String, destination: Path, cancelled: () -> Boolean): Path? =
        try {
            HttpRequests.request(url)
                .userAgent(userAgent)
                .connectTimeout(CONNECT_TIMEOUT_MS)
                .readTimeout(READ_TIMEOUT_MS)
                .throwStatusCodeException(false)
                .tuner { connection ->
                    authorizationFor(url)?.let { connection.setRequestProperty("Authorization", it) }
                }
                .connect { request ->
                    val connection = request.connection as HttpURLConnection
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        log.info("Staleguard: artifact fetch got HTTP ${connection.responseCode} for $url")
                        null
                    } else {
                        Files.createDirectories(destination.parent)
                        copy(request.inputStream, destination, cancelled)
                    }
                }
        } catch (e: Exception) {
            log.info("Staleguard: artifact fetch failed for $url: ${e.javaClass.simpleName}: ${e.message}")
            null
        }

    /** Streams to disk with a size ceiling so a mis-served response cannot fill the user's drive. */
    private fun copy(input: InputStream, destination: Path, cancelled: () -> Boolean): Path? {
        var written = 0L
        val buffer = ByteArray(BUFFER)
        Files.newOutputStream(destination).use { out ->
            while (true) {
                if (cancelled()) return null
                val read = input.read(buffer)
                if (read <= 0) break
                written += read
                if (written > MAX_ARTIFACT_BYTES) {
                    log.info("Staleguard: artifact exceeded ${MAX_ARTIFACT_BYTES / 1024 / 1024} MB, abandoning")
                    return null
                }
                out.write(buffer, 0, read)
            }
        }
        return destination
    }

    private fun extractClassesJar(aar: Path, destination: Path): Path? =
        try {
            ZipFile(aar.toFile()).use { zip ->
                val entry = zip.getEntry("classes.jar") ?: return null
                zip.getInputStream(entry).use { input ->
                    Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            destination
        } catch (e: Exception) {
            log.info("Staleguard: could not read classes.jar from $aar: ${e.message}")
            null
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000
        const val BUFFER = 16 * 1024
        const val MAX_ARTIFACT_BYTES = 200L * 1024 * 1024
    }
}

/** Artifact URLs derived from the .pom URL the routing layer already produces. */
object MavenArtifactUrls {

    /**
     * The same coordinates with a different packaging: .../foo-1.2.pom to
     * .../foo-1.2.jar. Deriving from the pom URL means jar fetches inherit
     * mirror routing, Google's dl.google.com base, and the Plugin Portal for
     * free, instead of duplicating that logic.
     */
    fun siblingWithExtension(pomUrl: String, extension: String): String =
        pomUrl.removeSuffix(".pom") + "." + extension
}
