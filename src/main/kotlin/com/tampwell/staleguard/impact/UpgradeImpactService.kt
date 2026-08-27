package com.tampwell.staleguard.impact

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.repository.RepositoryCredentials
import com.tampwell.staleguard.services.VersionLookupService
import com.tampwell.staleguard.settings.StaleguardSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * Answers the question an upgrade hint cannot: would this upgrade break *this*
 * project?
 *
 * Compares the two versions' binaries and reports only the removed members
 * this project actually calls. Every step is explicit-intent only — it
 * downloads a jar, so it never runs during highlighting.
 */
@Service(Service.Level.PROJECT)
class UpgradeImpactService(private val project: Project) {

    private val log = logger<UpgradeImpactService>()

    fun analyze(
        coordinates: Coordinates,
        fromVersion: String,
        toVersion: String,
        indicator: ProgressIndicator,
    ): ImpactReport {
        val coordinate = coordinates.toString()
        fun report(
            removedTotal: Int = 0,
            usages: List<RemovedUsage> = emptyList(),
            incomplete: ImpactReport.Incomplete? = null,
            truncated: Boolean = false,
        ) = ImpactReport(coordinate, fromVersion, toVersion, removedTotal, usages, incomplete, truncated)
            .also { ImpactMemory.getInstance(project).record(it) }

        val cache = RemovedMembersCache(cacheDirectory())
        val cached = cache.read(coordinates, fromVersion, toVersion)
        if (cached != null) {
            val found = RemovedMemberUsageSearch.find(project, cached, indicator)
            return report(cached.size, found.usages, truncated = !found.searchedAll)
        }

        if (StaleguardSettings.getInstance().state.offlineMode) return report(incomplete = ImpactReport.Incomplete.OFFLINE)

        val classpath = ProjectClasspath.libraryJars(project)
        val workspace = Files.createTempDirectory("staleguard-impact")
        try {
            indicator.text2 = fromVersion
            val currentJar = ProjectClasspath.findArtifactJar(classpath, coordinates.artifactId, fromVersion)
                ?: fetch(coordinates, fromVersion, workspace.resolve("current.jar"), indicator)
                ?: return report(incomplete = ImpactReport.Incomplete.CURRENT_JAR_UNAVAILABLE)

            indicator.text2 = toVersion
            val candidateJar = fetch(coordinates, toVersion, workspace.resolve("candidate.jar"), indicator)
                ?: return report(incomplete = ImpactReport.Incomplete.CANDIDATE_JAR_UNAVAILABLE)

            indicator.checkCanceled()
            // A null read means cancellation, and the only correct response is
            // to abandon the analysis: reporting on half a jar would invent
            // removals, and reporting on none would invent an all-clear.
            val current = JarApiReader.read(currentJar) { indicator.isCanceled } ?: throw ProcessCanceledException()
            val candidate = JarApiReader.read(candidateJar) { indicator.isCanceled } ?: throw ProcessCanceledException()

            // Supertypes are looked up in the rest of the project's classpath:
            // a class's parent usually ships in a sibling jar, and without it
            // every inherited member would read as removed.
            val removed = ClasspathClassLookup(classpath.filter { it != currentJar }).use { supertypes ->
                current.removedIn(candidate, supertypes)
            }
            cache.write(coordinates, fromVersion, toVersion, removed)

            val found = RemovedMemberUsageSearch.find(project, removed, indicator)
            return report(removed.size, found.usages, truncated = !found.searchedAll)
        } finally {
            runCatching { Files.walk(workspace).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun fetch(
        coordinates: Coordinates,
        version: String,
        destination: Path,
        indicator: ProgressIndicator,
    ): Path? {
        val fetcher = HttpArtifactJarFetcher(pluginVersion()) { url ->
            RepositoryCredentials.getInstance().forUrl(url)?.let { credentials ->
                val user = credentials.userName ?: return@let null
                val password = credentials.password?.toCharArray() ?: return@let null
                RepositoryCredentials.basicAuthValue(user, password)
            }
        }
        for (pomUrl in VersionLookupService.getInstance().pomUrls(coordinates, version)) {
            indicator.checkCanceled()
            fetcher.fetch(pomUrl, destination) { indicator.isCanceled }?.let { return it }
        }
        log.info("Staleguard: no binary found for $coordinates:$version in any configured repository")
        return null
    }

    companion object {
        fun getInstance(project: Project): UpgradeImpactService = project.service()

        /** Also read by the settings page for stats and clearing — one owner for the path. */
        fun cacheDirectory(): Path =
            Path.of(PathManager.getSystemPath(), "staleguard", "impact-cache")

        private fun pluginVersion(): String = com.tampwell.staleguard.StaleguardVersion.current()
    }
}
