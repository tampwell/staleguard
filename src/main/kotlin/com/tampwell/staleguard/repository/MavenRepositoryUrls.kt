package com.tampwell.staleguard.repository

/** Group + artifact, the cache key for everything in this package. */
data class Coordinates(val groupId: String, val artifactId: String) {
    override fun toString(): String = "$groupId:$artifactId"
}

/**
 * URL building for Maven-layout repositories. Pure string logic.
 *
 * We deliberately fetch static `maven-metadata.xml` files instead of using the
 * central search API: they're small, CDN-cached, ETag-revalidatable, and work
 * identically against any Maven-layout repository (Central, Google, private
 * Nexus) — and Sonatype's 429 guidance points high-volume users away from the
 * search index.
 */
object MavenRepositoryUrls {

    const val MAVEN_CENTRAL = "https://repo1.maven.org/maven2"

    fun metadataUrl(repositoryBaseUrl: String, coordinates: Coordinates): String =
        "${repositoryBaseUrl.trimEnd('/')}/${coordinates.groupId.replace('.', '/')}/${coordinates.artifactId}/maven-metadata.xml"

    /** The .pom of a concrete version — its Last-Modified header is that version's release date. */
    fun pomUrl(repositoryBaseUrl: String, coordinates: Coordinates, version: String): String =
        "${repositoryBaseUrl.trimEnd('/')}/${coordinates.groupId.replace('.', '/')}/${coordinates.artifactId}/$version/${coordinates.artifactId}-$version.pom"
}
