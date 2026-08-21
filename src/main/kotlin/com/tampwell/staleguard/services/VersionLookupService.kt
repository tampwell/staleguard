package com.tampwell.staleguard.services

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.tampwell.staleguard.repository.ArtifactVersions
import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.repository.DiskVersionCache
import com.tampwell.staleguard.repository.HttpMavenRepositoryClient
import com.tampwell.staleguard.repository.PeekResult
import com.tampwell.staleguard.repository.SourceRouter
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

    private val engine = run {
        val client = HttpMavenRepositoryClient(pluginVersion())
        VersionLookupEngine(
            scope = scope,
            client = client,
            cache = DiskVersionCache(cacheDirectory()),
            ioDispatcher = Dispatchers.IO,
            router = SourceRouter.default(
                client,
                extras = com.tampwell.staleguard.repository.ExtraRepositories.getInstance()::sources,
                centralRoute = { MavenMirrorService.getInstance().route() },
            ),
        )
    }

    suspend fun lookup(coordinates: Coordinates, force: Boolean = false): ArtifactVersions? {
        engine.offlineMode = com.tampwell.staleguard.settings.StaleguardSettings.getInstance().state.offlineMode
        return engine.lookup(coordinates, force)
    }

    /**
     * Synchronous, I/O-free warm-cache read — the ONLY lookup API that
     * highlighting passes may call. Null = never resolved this session;
     * PeekResult(value = null) = artifact known absent, do not re-enqueue.
     */
    fun peek(coordinates: Coordinates): PeekResult? = engine.peek(coordinates)

    fun cacheStats(): Pair<Int, Long> = engine.cacheStats()

    fun clearCache() = engine.clearCache()

    companion object {
        fun getInstance(): VersionLookupService = service()

        private fun cacheDirectory(): Path =
            Path.of(PathManager.getSystemPath(), "staleguard", "version-cache")

        /** Baked in at build time via processResources — no platform API needed. */
        private fun pluginVersion(): String =
            VersionLookupService::class.java.getResourceAsStream("/staleguard.properties")
                ?.use { stream -> java.util.Properties().apply { load(stream) }.getProperty("version") }
                ?: "dev"
    }
}
