package com.tampwell.staleguard.services

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.tampwell.staleguard.repository.ArtifactVersions
import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.repository.DiskVersionCache
import com.tampwell.staleguard.repository.HttpMavenRepositoryClient
import com.tampwell.staleguard.repository.VersionLookupEngine
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * IDE-wide entry point for version lookups. Thin platform shell around
 * [VersionLookupEngine] — the scope is injected by the platform (never create
 * your own), the cache lives under the IDE's system directory, and all I/O
 * happens on Dispatchers.IO inside the engine.
 */
@Service(Service.Level.APP)
class VersionLookupService(scope: CoroutineScope) {

    private val engine = VersionLookupEngine(
        scope = scope,
        client = HttpMavenRepositoryClient(pluginVersion()),
        cache = DiskVersionCache(cacheDirectory()),
        ioDispatcher = Dispatchers.IO,
    )

    suspend fun lookup(coordinates: Coordinates): ArtifactVersions? = engine.lookup(coordinates)

    companion object {
        fun getInstance(): VersionLookupService = service()

        private fun cacheDirectory(): Path =
            Path.of(PathManager.getSystemPath(), "staleguard", "version-cache")

        private fun pluginVersion(): String =
            PluginManagerCore.getPlugin(PluginId.getId("com.tampwell.staleguard"))?.version ?: "dev"
    }
}
