package com.tampwell.staleguard.reach

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.impact.ArtifactJars
import com.tampwell.staleguard.impact.JarCoordinates
import com.tampwell.staleguard.impact.MemberRef
import com.tampwell.staleguard.impact.ModuleOutputs
import com.tampwell.staleguard.impact.ModuleScopes
import com.tampwell.staleguard.impact.ProjectClasspath
import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.security.AdvisoryPeek
import com.tampwell.staleguard.security.OsvAdvisory
import com.tampwell.staleguard.services.FreshnessRefreshService
import com.tampwell.staleguard.services.VersionLookupService
import com.tampwell.staleguard.services.VulnerabilityService
import com.tampwell.staleguard.settings.StaleguardSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * Answers, for every vulnerable artifact on this project's classpaths:
 * does the project's own code reach anything the fix changed?
 *
 * Explicit action only. Fixed releases are downloaded when a diff is not yet
 * cached, and automatic work must never surprise the network. Once diffs are
 * cached every later run is local.
 */
@Service(Service.Level.PROJECT)
class ReachabilityService(private val project: Project) {

    data class Summary(
        val vulnerable: Int,
        val reached: Int,
        val reachedInTestsOnly: Int,
        val notReached: Int,
        val unknown: Int,
        /** Artifacts whose vulnerability data was not cached yet; a later run will cover them. */
        val notYetLookedUp: Int,
        val notBuilt: Boolean,
    )

    /** One classpath the project runs with: its compiled classes and the jars they run against. */
    private data class Scope(val name: String, val tests: Boolean, val outputDirs: List<Path>, val jars: List<Path>)

    /** One vulnerable resolved artifact; the same coordinates can sit in several caches. */
    private class Finding(
        val coordinates: Coordinates,
        val version: String,
        val jars: Set<Path>,
        val advisories: List<OsvAdvisory>,
    )

    private val cache = FixDiffCache(Path.of(PathManager.getSystemPath(), "staleguard", "fix-diffs"))

    /**
     * Where advisories come from: the warm OSV cache. A seam only for tests,
     * which must not depend on osv.dev answering.
     */
    internal var advisories: (Coordinates, String) -> AdvisoryPeek? = { coordinates, version ->
        VulnerabilityService.getInstance().peek(coordinates, version)
    }

    fun check(indicator: ProgressIndicator): Summary {
        indicator.isIndeterminate = false
        indicator.text = StaleguardBundle.message("reach.progress.scopes")
        val scopes = scopes()
        val (findings, cold) = findings(scopes)
        val verdicts = LinkedHashMap<ReachabilityState.Key, ReachVerdict>()
        fun key(finding: Finding, advisory: OsvAdvisory) = ReachabilityState.Key(
            finding.coordinates.groupId, finding.coordinates.artifactId, finding.version, advisory.id,
        )

        val walkable = scopes.filter { scope -> scope.outputDirs.any(::hasClasses) }
        if (findings.isNotEmpty() && walkable.isEmpty()) {
            for (finding in findings) {
                for (advisory in finding.advisories) verdicts[key(finding, advisory)] = ReachVerdict.Unknown(ReachVerdict.Reason.NOT_BUILT)
            }
            return publish(verdicts, findings, cold, notBuilt = true)
        }

        // Fix diffs first: they decide which classpaths need walking at all.
        indicator.text = StaleguardBundle.message("reach.progress.diffs")
        val touchedBy = LinkedHashMap<Pair<Finding, OsvAdvisory>, Set<MemberRef>>()
        findings.forEachIndexed { index, finding ->
            indicator.checkCanceled()
            indicator.fraction = DIFF_SHARE * index / findings.size
            indicator.text2 = "${finding.coordinates}:${finding.version}"
            for (advisory in finding.advisories) {
                val fixed = advisory.fixedVersion
                val touched = when {
                    fixed == null -> {
                        verdicts[key(finding, advisory)] = ReachVerdict.Unknown(ReachVerdict.Reason.NO_FIX_VERSION)
                        null
                    }
                    else -> touchedFor(finding, fixed, indicator).also {
                        if (it == null) verdicts[key(finding, advisory)] = ReachVerdict.Unknown(ReachVerdict.Reason.FIX_NOT_FETCHED)
                    }
                }
                if (touched != null) touchedBy[finding to advisory] = touched
            }
        }

        // Walk each distinct classpath that carries an artifact still needing an answer.
        indicator.text = StaleguardBundle.message("reach.progress.walk")
        val needed = touchedBy.keys.flatMapTo(HashSet()) { it.first.jars }
        val groups = walkable.filter { scope -> scope.jars.any { it in needed } }
            .groupBy { it.outputDirs.sorted() to it.jars.sorted() }
            .values.toList()
        val observations = HashMap<Pair<Finding, OsvAdvisory>, MutableList<ReachVerdicts.Observation>>()
        groups.forEachIndexed { index, group ->
            indicator.checkCanceled()
            indicator.fraction = DIFF_SHARE + (1 - DIFF_SHARE) * index / groups.size
            indicator.text2 = group.first().name
            val scope = group.first()
            // Test-only if every scope sharing this classpath is a test scope.
            val tests = group.all { it.tests }
            val result = ClasspathClassSource.open(scope.outputDirs + scope.jars).use { source ->
                Reachability.analyze(
                    source,
                    entryClasses(scope.outputDirs),
                    checkCanceled = { indicator.checkCanceled() },
                )
            }
            // Observe and drop: many modules must not mean many live walks.
            for ((pair, touched) in touchedBy) {
                if (pair.first.jars.none { it in scope.jars }) continue
                observations.getOrPut(pair) { ArrayList() } += ReachVerdicts.observe(touched, result, tests)
            }
        }

        for ((pair, touched) in touchedBy) {
            val (finding, advisory) = pair
            val seen = observations[pair].orEmpty()
            verdicts[key(finding, advisory)] = when {
                seen.isNotEmpty() -> ReachVerdicts.decide(touched, seen)
                // Carried only by modules that have not been built.
                scopes.any { scope -> scope.jars.any { it in finding.jars } } ->
                    ReachVerdict.Unknown(ReachVerdict.Reason.NOT_BUILT)
                else -> ReachVerdict.Unknown(ReachVerdict.Reason.JAR_NOT_FOUND)
            }
        }
        return publish(verdicts, findings, cold, notBuilt = false)
    }

    private fun publish(
        verdicts: Map<ReachabilityState.Key, ReachVerdict>,
        findings: List<Finding>,
        cold: Int,
        notBuilt: Boolean,
    ): Summary {
        ReachabilityState.getInstance(project).record(verdicts)
        project.messageBus.syncPublisher(ReachabilityListener.TOPIC).verdictsChanged()
        FreshnessRefreshService.getInstance(project).repaintBuildFiles()
        val values = verdicts.values
        return Summary(
            vulnerable = findings.size,
            reached = values.count { it is ReachVerdict.Reached && it.inProduction },
            reachedInTestsOnly = values.count { it is ReachVerdict.Reached && !it.inProduction },
            notReached = values.count { it is ReachVerdict.NotReached },
            unknown = values.count { it is ReachVerdict.Unknown },
            notYetLookedUp = cold,
            notBuilt = notBuilt,
        )
    }

    /**
     * The same per-module views the linkage doctor audits: production and
     * test classpaths separately, each with the compiled classes that run on
     * it, sibling modules included, since a sibling is the user's code too.
     */
    private fun scopes(): List<Scope> {
        val modules = ModuleScopes.collect(project)
        val production = ModuleOutputs.directories(project, tests = false)
        val tests = ModuleOutputs.directories(project, tests = true)
        if (modules.isEmpty()) {
            return listOf(Scope(project.name, false, production.values.flatten(), ProjectClasspath.libraryJars(project)))
        }
        return modules.flatMap { module ->
            listOf(
                Scope(
                    name = module.moduleName,
                    tests = false,
                    outputDirs = module.productionClosure.flatMap { production[it].orEmpty() },
                    jars = module.productionJarPaths,
                ),
                Scope(
                    name = ModuleScopes.testScopeName(module.moduleName),
                    tests = true,
                    outputDirs = module.testClosure.flatMap { production[it].orEmpty() } +
                        tests[module.moduleName].orEmpty(),
                    jars = module.testJarPaths,
                ),
            )
        }
    }

    /** Vulnerable artifacts on the classpaths, from the warm advisory cache; cold ones are queued for next time. */
    private fun findings(scopes: List<Scope>): Pair<List<Finding>, Int> {
        val refresh = FreshnessRefreshService.getInstance(project)
        val byVersion = LinkedHashMap<Pair<Coordinates, String>, MutableSet<Path>>()
        for (jar in scopes.flatMap { it.jars }.distinct()) {
            val identified = JarCoordinates.identify(jar) ?: continue
            if (identified.version.endsWith("-SNAPSHOT", ignoreCase = true)) continue
            byVersion.getOrPut(identified.coordinates to identified.version) { LinkedHashSet() }.add(jar)
        }
        var cold = 0
        val findings = byVersion.mapNotNull { (id, jars) ->
            val (coordinates, version) = id
            val peek = advisories(coordinates, version)
            if (peek == null || peek.failed) {
                cold++
                refresh.requestVulnerabilityLookup(coordinates, version)
                return@mapNotNull null
            }
            peek.advisories?.takeIf { it.isNotEmpty() }?.let { Finding(coordinates, version, jars, it) }
        }
        return findings to cold
    }

    /**
     * What the fix changed, as methods of the project's version. The narrow
     * window (last stable release before the fix vs the fix) when it maps
     * onto the project's version; otherwise the project's version against
     * the fix, which also counts unrelated changes and so errs toward
     * "reached", never toward a false "not reached".
     */
    private fun touchedFor(finding: Finding, fixed: String, indicator: ProgressIndicator): Set<MemberRef>? {
        val lookup = VersionLookupService.getInstance()
        val versions = lookup.peek(finding.coordinates)?.value?.versions
        if (versions == null) FreshnessRefreshService.getInstance(project).requestLookup(finding.coordinates)
        val baseline = FixWindow.baseline(versions.orEmpty(), finding.version, fixed)
        if (baseline != null) {
            val window = diff(finding.coordinates, baseline, fixed, localFrom = null, indicator)
            val mapped = window?.let { result ->
                ClasspathClassSource.open(listOf(finding.jars.first())).use { FixWindow.mapOnto(result.touched, it) }
            }
            if (mapped != null) return mapped
        }
        return diff(finding.coordinates, finding.version, fixed, localFrom = finding.jars.first(), indicator)?.touched
    }

    /**
     * The diff between two releases, cached forever once computed. [localFrom]
     * is the project's own copy of [from] when it has one; anything missing
     * is downloaded through the shared fetcher, never in offline mode.
     */
    private fun diff(
        coordinates: Coordinates,
        from: String,
        to: String,
        localFrom: Path?,
        indicator: ProgressIndicator,
    ): FixDiff.Result? {
        cache.read(coordinates, from, to)?.let { return it }
        if (StaleguardSettings.getInstance().state.offlineMode) return null
        val workspace = Files.createTempDirectory("staleguard-reach")
        try {
            val fromJar = localFrom
                ?: ArtifactJars.fetch(coordinates, from, workspace.resolve("from.jar")) { indicator.isCanceled }
                ?: return null
            val toJar = ArtifactJars.fetch(coordinates, to, workspace.resolve("to.jar")) { indicator.isCanceled }
                ?: return null
            val result = ClasspathClassSource.open(listOf(fromJar)).use { before ->
                ClasspathClassSource.open(listOf(toJar)).use { after -> FixDiff.compare(before, after) }
            }
            cache.write(coordinates, from, to, result)
            return result
        } finally {
            runCatching {
                Files.walk(workspace).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
        }
    }

    private fun hasClasses(directory: Path): Boolean = runCatching {
        Files.walk(directory).use { stream -> stream.anyMatch { it.toString().endsWith(".class") } }
    }.getOrDefault(false)

    /** Every class in the project's own output: all of it is a starting point. */
    private fun entryClasses(directories: List<Path>): List<String> = directories.flatMap { root ->
        runCatching {
            Files.walk(root).use { stream ->
                stream.filter { it.toString().endsWith(".class") && !it.fileName.toString().equals("module-info.class") }
                    .map { root.relativize(it).joinToString("/").removeSuffix(".class") }
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        /** Progress share for fix diffs (downloads); the rest walks classpaths. */
        private const val DIFF_SHARE = 0.4

        fun getInstance(project: Project): ReachabilityService = project.service()
    }
}
