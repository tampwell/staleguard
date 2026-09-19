package com.tampwell.staleguard.impact

import com.intellij.openapi.diagnostic.logger
import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.repository.RepositoryCredentials
import com.tampwell.staleguard.services.VersionLookupService
import java.nio.file.Path

/**
 * The one way Staleguard downloads a released binary: every configured
 * repository in routing order, credentials only for the hosts the user
 * listed, through the platform's HTTP stack so proxies and certificates are
 * honored. Every feature that needs a jar (impact analysis, fix probes,
 * reachability fix diffs) comes through here, so none can drift into its own
 * credential handling.
 */
internal object ArtifactJars {

    private val log = logger<ArtifactJars>()

    private val fetcher: ArtifactJarFetcher by lazy {
        HttpArtifactJarFetcher(com.tampwell.staleguard.StaleguardVersion.current()) { url ->
            RepositoryCredentials.getInstance().forUrl(url)?.let { credentials ->
                val user = credentials.userName ?: return@let null
                val password = credentials.password?.toCharArray() ?: return@let null
                RepositoryCredentials.basicAuthValue(user, password)
            }
        }
    }

    /** The jar for [coordinates]:[version] at [destination], or null when no repository has it. */
    fun fetch(coordinates: Coordinates, version: String, destination: Path, isCanceled: () -> Boolean): Path? {
        for (pomUrl in VersionLookupService.getInstance().pomUrls(coordinates, version)) {
            if (isCanceled()) return null
            fetcher.fetch(pomUrl, destination, isCanceled)?.let { return it }
        }
        log.info("Staleguard: no binary found for $coordinates:$version in any configured repository")
        return null
    }
}
