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
        val moduleCount: Int = 1,
        /** Which modules each finding holds in, by finding identity. */
        val findingModules: Map<LinkageDelta.Key, List<String>> = emptyMap(),
        /** Where each scanned jar lives, so a fix can identify its coordinates. */
        val jarPaths: Map<String, java.nio.file.Path> = emptyMap(),
        /** Rendered dependency paths per blamed jar: why that version is here. Maven only. */
        val provenance: Map<String, List<String>> = emptyMap(),
    )

    private class ScopeSet(
        val scopes: List<ScopedLinkage.Scope>,
        val pathByJarName: Map<String, java.nio.file.Path>,
        val standing: OwnCodeAudit.Standing,
        val allScans: List<LinkageAudit.JarScans>,
    )

    private fun buildScopes(indicator: ProgressIndicator): ScopeSet {
        val moduleScopes = ModuleScopes.collect(project)
        val jars = moduleScopes.flatMap { it.productionJarPaths + it.testJarPaths }.distinct()
            .ifEmpty { ProjectClasspath.libraryJars(project) }

        val pathByJarName = HashMap<String, java.nio.file.Path>()
        val scanByPath = HashMap<java.nio.file.Path, LinkageAudit.JarScans>()
        jars.forEachIndexed { index, jar ->
            indicator.checkCanceled()
            indicator.fraction = index.toDouble() / jars.size * SCAN_SHARE
            indicator.text2 = jar.fileName.toString()
            scansOf(jar)?.also {
                scanByPath[jar] = it
                pathByJarName.putIfAbsent(it.jarName, jar)
            }
        }

        // The user's own compiled classes join each scope where they run.
        // Their calls into the classpath are where a conflict actually bites,
        // and OwnCodeAudit.standing is what keeps a stale or partial build
        // from turning into a false promise.
        val outputs = ModuleOutputs.collect(project)
        val outputScans = outputs.mapNotNull { output ->
            output.scans?.takeIf { it.classes.isNotEmpty() }?.let { output.moduleName to it }
        }.toMap()
        val testOutputScans = ModuleOutputs.collectTestScans(project)

        val scopes = if (moduleScopes.isEmpty()) {
            listOf(ScopedLinkage.Scope(project.name, scanByPath.values.toList() + outputScans.values))
        } else {
            moduleScopes.flatMap { scope ->
                // A module's own test classes ride only its own test scope:
                // test output is not a dependency any other module resolves.
                listOf(
                    ScopedLinkage.Scope(
                        name = scope.moduleName,
                        jars = scope.productionJarPaths.mapNotNull(scanByPath::get) +
                            scope.productionClosure.mapNotNull(outputScans::get),
                    ),
                    ScopedLinkage.Scope(
                        name = ModuleScopes.testScopeName(scope.moduleName),
                        jars = scope.testJarPaths.mapNotNull(scanByPath::get) +
                            scope.testClosure.mapNotNull(outputScans::get) +
                            listOfNotNull(testOutputScans[scope.moduleName]),
                    ),
                )
            }
        }
        indicator.text2 = ""
        indicator.fraction = SCAN_SHARE
        return ScopeSet(
            scopes = scopes,
            pathByJarName = pathByJarName,
            standing = OwnCodeAudit.standing(outputs),
            allScans = scanByPath.values.toList() + outputScans.values,
        )
    }

    fun audit(indicator: ProgressIndicator, computeSuggestions: Boolean = true): Result {
        indicator.isIndeterminate = false
        val set = buildScopes(indicator)
        val scopes = set.scopes
        val standing = set.standing
        val pathByJarName = set.pathByJarName
        val platformMembers = PsiPlatformMembers(project)
        val merged = ScopedLinkage.run(
            scopes,
            platformMembers = { internalName, memberName ->
                indicator.checkCanceled()
                platformMembers.has(internalName, memberName)
            },
            onScopeDone = { finished, total ->
                indicator.fraction = SCAN_SHARE + (1 - SCAN_SHARE) * finished / total
            },
        )
        val suggestions = if (computeSuggestions) {
            suggestionsFor(merged.report, set.allScans, pathByJarName, indicator)
        } else {
            // The background watcher stays local-only: fix probes download
            // jars, and automatic work must never surprise the network.
            emptyMap()
        }
        val provenance = provenanceFor(merged.report, pathByJarName)
        LinkageVerdictState.getInstance(project).record(
            merged.report,
            identify = { jarName -> pathByJarName[jarName]?.let(JarCoordinates::identify) },
            fixFor = { jarName -> (suggestions[jarName] as? FixSuggestions.Suggestion.FixedIn)?.version },
            provenanceFor = { jarName -> provenance[jarName].orEmpty() },
        )
        return Result(
            merged.report, standing, suggestions,
            merged.moduleCount, merged.modulesByFinding, pathByJarName, provenance,
        )
    }

    /**
     * Why each blamed jar's version is on the classpath: dependency paths from
     * the IDE's own Maven resolution. Empty for Gradle builds — no resolved
     * tree exists in the IDE, and a guessed path is worse than none.
     */
    private fun provenanceFor(
        report: LinkageAudit.Report,
        pathByJarName: Map<String, java.nio.file.Path>,
    ): Map<String, List<String>> {
        val blamed = (
            report.brokenMembers.mapNotNull { it.ownerJar } +
                report.shadowedGroups.flatMap { it.shadowedJars + it.winnerJar }
            ).distinct()
        if (blamed.isEmpty()) return emptyMap()
        val roots = MavenProvenance.nodesFor(project)
        if (roots.isEmpty()) return emptyMap()
        return blamed.mapNotNull { jarName ->
            val coordinates = pathByJarName[jarName]?.let(JarCoordinates::identify)?.coordinates
                ?: return@mapNotNull null
            val paths = ProvenanceTrace.trace(roots, coordinates.groupId, coordinates.artifactId)
            paths.takeIf { it.isNotEmpty() }?.let { jarName to it.map(ProvenanceTrace.Path::render) }
        }.toMap()
    }

    /**
     * Rehearses upgrading [currentJar] to [candidateJar] against every scope.
     * Null when the current jar is not actually on any scope's classpath —
     * a rehearsal of a jar nobody resolves would answer a different question.
     */
    fun rehearseUpgrade(
        indicator: ProgressIndicator,
        currentJar: java.nio.file.Path,
        candidateJar: java.nio.file.Path,
    ): ImpactReport.Rehearsal? {
        val currentScans = scansOf(currentJar) ?: return null
        val replacement = JarScanner.scan(candidateJar) ?: return null
        indicator.isIndeterminate = false
        val set = buildScopes(indicator)
        if (set.scopes.none { scope -> scope.jars.any { it.jarName == currentScans.jarName } }) return null
        val platformMembers = PsiPlatformMembers(project)
        val verdict = UpgradeRehearsal.rehearse(set.scopes, currentScans.jarName, replacement) { name, member ->
            indicator.checkCanceled()
            platformMembers.has(name, member)
        }
        return ImpactReport.Rehearsal(verdict.fixedLines, verdict.introducedLines)
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
