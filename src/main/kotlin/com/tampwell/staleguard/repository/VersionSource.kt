package com.tampwell.staleguard.repository

import org.w3c.dom.Element

/**
 * Where to look up an artifact and how to read the answer. Central and the
 * Gradle Plugin Portal speak maven-metadata.xml; Google's repository (androidx
 * and friends) publishes one group-index.xml per group, listing every
 * artifact's versions in a comma-separated attribute.
 */
interface VersionSource {

    fun metadataUrl(coordinates: Coordinates): String

    /** This artifact's versions, or empty when the body doesn't list it. */
    @Throws(MetadataParseException::class)
    fun versionsIn(body: String, coordinates: Coordinates): List<String>

    fun pomUrl(coordinates: Coordinates, version: String): String
}

/** Any repository with standard Maven layout and maven-metadata.xml. */
class MavenLayoutSource(private val baseUrl: String) : VersionSource {

    override fun metadataUrl(coordinates: Coordinates) =
        MavenRepositoryUrls.metadataUrl(baseUrl, coordinates)

    override fun versionsIn(body: String, coordinates: Coordinates) =
        MavenMetadata.parse(body).versions.map { it.value }

    override fun pomUrl(coordinates: Coordinates, version: String) =
        MavenRepositoryUrls.pomUrl(baseUrl, coordinates, version)
}

/**
 * Google's Maven repository. maven.google.com 301s every request, so URLs are
 * built against dl.google.com directly. Poms and artifacts still use standard
 * Maven layout under the same base.
 */
class GoogleMavenSource : VersionSource {

    override fun metadataUrl(coordinates: Coordinates) =
        "$BASE_URL/${coordinates.groupId.replace('.', '/')}/group-index.xml"

    override fun versionsIn(body: String, coordinates: Coordinates): List<String> {
        val root = SecureXml.parse(body, "group-index.xml").documentElement
            ?: throw MetadataParseException("Empty group-index.xml")
        var child = root.firstChild
        while (child != null) {
            if (child is Element && child.tagName == coordinates.artifactId) {
                return child.getAttribute("versions").split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
            }
            child = child.nextSibling
        }
        return emptyList()
    }

    override fun pomUrl(coordinates: Coordinates, version: String) =
        MavenRepositoryUrls.pomUrl(BASE_URL, coordinates, version)

    companion object {
        const val BASE_URL = "https://dl.google.com/dl/android/maven2"
    }
}

/**
 * Membership set for Google's repository, from its master-index.xml (one
 * element per group). Successful fetches are held for 24h, failures for 1h so
 * an outage doesn't hammer the endpoint. Thread-safe; callers may block on
 * first use — the engine only consults this on Dispatchers.IO.
 */
class GoogleMasterIndex(
    private val client: MavenRepositoryClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private var groups: Set<String>? = null
    private var fetchedAt = 0L
    private var lastFailureAt: Long? = null

    @Synchronized
    fun groups(): Set<String>? {
        val now = clock()
        groups?.takeIf { now - fetchedAt < SUCCESS_TTL_MILLIS }?.let { return it }
        lastFailureAt?.let { if (now - it < FAILURE_TTL_MILLIS) return groups }

        when (val result = client.fetchMetadata("${GoogleMavenSource.BASE_URL}/master-index.xml", null)) {
            is FetchResult.Fetched -> {
                val parsed = try {
                    parseGroups(result.body)
                } catch (_: MetadataParseException) {
                    lastFailureAt = now
                    return groups
                }
                groups = parsed
                fetchedAt = now
                lastFailureAt = null
            }

            else -> lastFailureAt = now
        }
        return groups
    }

    private fun parseGroups(body: String): Set<String> {
        val root = SecureXml.parse(body, "master-index.xml").documentElement
            ?: throw MetadataParseException("Empty master-index.xml")
        val found = mutableSetOf<String>()
        var child = root.firstChild
        while (child != null) {
            if (child is Element) found += child.tagName
            child = child.nextSibling
        }
        return found
    }

    companion object {
        const val SUCCESS_TTL_MILLIS: Long = 24 * 60 * 60 * 1000
        const val FAILURE_TTL_MILLIS: Long = 60 * 60 * 1000
    }
}

/**
 * Decides, per coordinates, which repositories can answer and in what order.
 *
 *  - Plugin markers (artifactId ends in .gradle.plugin) try the Plugin Portal
 *    first; some ecosystems (Kotlin) also publish markers to Central.
 *  - androidx / com.android / android.* exist only on Google — Google first,
 *    Central as an escape hatch.
 *  - Other groups in Google's master index (com.google.firebase, but also
 *    Central-published groups Google mirrors, like org.jetbrains.*) prefer
 *    Central so version lists never lag a partial mirror, and fall back to
 *    Google for the Google-exclusive ones.
 *  - Everything else is Central, as before v1.2.
 */
class SourceRouter(
    private val central: VersionSource,
    private val google: VersionSource,
    private val pluginPortal: VersionSource,
    private val googleGroups: () -> Set<String>?,
) {

    fun sourcesFor(coordinates: Coordinates): List<VersionSource> {
        val group = coordinates.groupId
        return when {
            coordinates.artifactId.endsWith(".gradle.plugin") -> listOf(pluginPortal, central)
            GOOGLE_ONLY_PREFIXES.any { group == it || group.startsWith("$it.") } -> listOf(google, central)
            googleGroups()?.contains(group) == true -> listOf(central, google)
            else -> listOf(central)
        }
    }

    companion object {
        const val PLUGIN_PORTAL_URL = "https://plugins.gradle.org/m2"

        private val GOOGLE_ONLY_PREFIXES = listOf("androidx", "com.android", "android")

        fun default(client: MavenRepositoryClient, clock: () -> Long = System::currentTimeMillis): SourceRouter {
            val index = GoogleMasterIndex(client, clock)
            return SourceRouter(
                central = MavenLayoutSource(MavenRepositoryUrls.MAVEN_CENTRAL),
                google = GoogleMavenSource(),
                pluginPortal = MavenLayoutSource(PLUGIN_PORTAL_URL),
                googleGroups = index::groups,
            )
        }

        /** Central-only routing — the pre-v1.2 behavior, used as test default. */
        fun centralOnly(): SourceRouter = SourceRouter(
            central = MavenLayoutSource(MavenRepositoryUrls.MAVEN_CENTRAL),
            google = GoogleMavenSource(),
            pluginPortal = MavenLayoutSource(PLUGIN_PORTAL_URL),
            googleGroups = { null },
        )
    }
}
