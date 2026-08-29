package com.tampwell.staleguard.impact

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the [LinkageAudit] over this project's resolved classpath.
 *
 * JDK membership questions go to the project SDK through PSI, because the
 * audit is meaningless without real JDK resolution (every hierarchy reaches
 * Object) and the SDK index answers for exactly the Java version this project
 * compiles against — reflection against the IDE's own runtime would answer
 * for the wrong JDK.
 */
@Service(Service.Level.PROJECT)
class ClasspathLinkageService(private val project: Project) {

    /** Jar scans keyed by path and modification time, so repeat runs re-read nothing. */
    private val scanCache = ConcurrentHashMap<Path, Pair<FileTime, LinkageAudit.JarScans>>()

    data class Result(
        val report: LinkageAudit.Report,
        val ownCode: OwnCodeAudit.Standing,
        /** Per broken jar: the earliest version that fixes it, when computable. */
        val suggestions: Map<String, FixSuggestions.Suggestion> = emptyMap(),
    )

    fun audit(indicator: ProgressIndicator): Result {
        val jars = ProjectClasspath.libraryJars(project)
        indicator.isIndeterminate = false

        val pathByJarName = HashMap<String, java.nio.file.Path>()
        val scans = jars.mapIndexedNotNull { index, jar ->
            indicator.checkCanceled()
            indicator.fraction = index.toDouble() / jars.size * SCAN_SHARE
            indicator.text2 = jar.fileName.toString()
            scansOf(jar)?.also { pathByJarName.putIfAbsent(it.jarName, jar) }
        }.toMutableList()

        // The user's own compiled classes join as one more scan set. Their
        // calls into the classpath are where a conflict actually bites, and
        // OwnCodeAudit.standing is what keeps a stale or partial build from
        // turning into a false promise.
        val outputs = ModuleOutputs.collect(project)
        val standing = OwnCodeAudit.standing(outputs)
        scans += OwnCodeAudit.auditableScans(outputs)

        indicator.text2 = ""
        indicator.fraction = SCAN_SHARE
        val platformMembers = PsiPlatformMembers(project)
        val report = LinkageAudit.run(scans) { internalName, memberName ->
            indicator.checkCanceled()
            platformMembers.has(internalName, memberName)
        }
        return Result(report, standing, suggestionsFor(report, scans, pathByJarName, indicator))
    }

    /**
     * Fix suggestions run only when there are findings, and each candidate
     * probe is a jar download through the same routed, credentialed fetcher
     * the impact check uses, bounded by [FixResolver.MAX_PROBES] per jar. A
     * cold version cache yields no suggestion this run rather than a surprise
     * network fan-out.
     */
    private fun suggestionsFor(
        report: LinkageAudit.Report,
        scans: List<LinkageAudit.JarScans>,
        pathByJarName: Map<String, java.nio.file.Path>,
        indicator: ProgressIndicator,
    ): Map<String, FixSuggestions.Suggestion> {
        if (report.clean) return emptyMap()
        indicator.text2 = com.tampwell.staleguard.StaleguardBundle.message("linkage.suggesting")

        val jarByPackage = HashMap<String, String>()
        for (jarScans in scans) {
            for (scan in jarScans.classes) {
                val pkg = scan.internalName.substringBeforeLast('/', "")
                jarByPackage.putIfAbsent(pkg, jarScans.jarName)
            }
        }
        val lookup = com.tampwell.staleguard.services.VersionLookupService.getInstance()
        val policy = com.tampwell.staleguard.policy.ProjectPolicyService.getInstance(project)
        val fetcher = HttpArtifactJarFetcher(com.tampwell.staleguard.StaleguardVersion.current()) { url ->
            com.tampwell.staleguard.repository.RepositoryCredentials.getInstance().forUrl(url)?.let { credentials ->
                val user = credentials.userName ?: return@let null
                val password = credentials.password?.toCharArray() ?: return@let null
                com.tampwell.staleguard.repository.RepositoryCredentials.basicAuthValue(user, password)
            }
        }

        val sources = FixSuggestions.Sources(
            identify = { jarName -> pathByJarName[jarName]?.let(JarCoordinates::identify) },
            packageOwner = { pkg -> jarByPackage[pkg] },
            versionsFor = { coords -> lookup.peek(coords)?.value?.versions?.map { it.value } },
            versionAllowed = { coords, current, candidate ->
                policy.versionAllowed(coords.groupId, coords.artifactId, current, candidate)
            },
            probe = { coords, version ->
                indicator.checkCanceled()
                indicator.text2 = "$coords $version"
                val workspace = java.nio.file.Files.createTempDirectory("staleguard-fix")
                try {
                    lookup.pomUrls(coords, version).firstNotNullOfOrNull { pomUrl ->
                        fetcher.fetch(pomUrl, workspace.resolve("candidate.jar")) { indicator.isCanceled }
                    }?.let { JarScanner.scan(it) }
                } finally {
                    runCatching {
                        java.nio.file.Files.walk(workspace)
                            .sorted(Comparator.reverseOrder())
                            .forEach(java.nio.file.Files::deleteIfExists)
                    }
                }
            },
        )
        return FixSuggestions.compute(report, sources)
    }

    private fun scansOf(jar: Path): LinkageAudit.JarScans? {
        val modified = runCatching { Files.getLastModifiedTime(jar) }.getOrNull() ?: return null
        scanCache[jar]?.takeIf { it.first == modified }?.let { return it.second }
        val scans = JarScanner.scan(jar) ?: return null
        scanCache[jar] = modified to scans
        return scans
    }

    companion object {
        fun getInstance(project: Project): ClasspathLinkageService = project.service()

        /** Share of the progress bar spent scanning jars; the rest is resolution. */
        private const val SCAN_SHARE = 0.7
    }
}
