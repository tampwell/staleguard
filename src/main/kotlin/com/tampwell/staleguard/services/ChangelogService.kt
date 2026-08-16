package com.tampwell.staleguard.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.tampwell.staleguard.changelog.ChangelogEngine
import com.tampwell.staleguard.repository.HttpMavenRepositoryClient
import java.util.concurrent.ConcurrentHashMap

/**
 * App-wide changelog lookups. Fetches happen only on explicit user intent
 * (the "Show what changed" fix), never from highlighting, and a
 * (coordinates, from, to) answer is immutable — cached for the session, so
 * repeat opens cost nothing against the anonymous GitHub rate limit.
 */
@Service(Service.Level.APP)
class ChangelogService {

    private val engine = ChangelogEngine(HttpMavenRepositoryClient(pluginVersion()))

    private data class Key(val coordinate: String, val from: String, val to: String)

    /** null result = "nothing found" and is cached too — retrying won't invent notes. */
    private val cache = ConcurrentHashMap<Key, java.util.Optional<ChangelogEngine.Summary>>()

    /** Blocking; call from a background task with progress, never the EDT. */
    fun summarize(
        coordinate: String,
        scmValue: String?,
        artifactId: String?,
        currentVersion: String,
        suggestedVersion: String,
        allVersions: List<String>,
    ): ChangelogEngine.Summary? =
        cache.computeIfAbsent(Key(coordinate, currentVersion, suggestedVersion)) {
            java.util.Optional.ofNullable(
                engine.summarize(scmValue, artifactId, currentVersion, suggestedVersion, allVersions),
            )
        }.orElse(null)

    companion object {
        fun getInstance(): ChangelogService = service()

        private fun pluginVersion(): String =
            ChangelogService::class.java.getResourceAsStream("/staleguard.properties")
                ?.use { stream -> java.util.Properties().apply { load(stream) }.getProperty("version") }
                ?: "dev"
    }
}
