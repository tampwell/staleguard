package com.tampwell.staleguard.checkup

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.gradle.LockfileScan
import com.tampwell.staleguard.impact.ClasspathLinkageService
import com.tampwell.staleguard.impact.OwnCodeAudit
import com.tampwell.staleguard.impact.Provenance
import com.tampwell.staleguard.impact.TransitiveVulnScan
import com.tampwell.staleguard.plan.StatsCalculator
import com.tampwell.staleguard.plan.UpgradePlanner
import com.tampwell.staleguard.reach.ReachMessages
import com.tampwell.staleguard.reach.ReachVerdict
import com.tampwell.staleguard.reach.ReachabilityService
import com.tampwell.staleguard.reach.ReachabilityState
import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.security.OsvAdvisory
import com.tampwell.staleguard.security.VulnKey
import com.tampwell.staleguard.services.FreshnessListener
import com.tampwell.staleguard.services.FreshnessRefreshService
import com.tampwell.staleguard.services.VersionLookupService
import com.tampwell.staleguard.services.VulnerabilityService
import com.tampwell.staleguard.settings.StaleguardSettings
import com.tampwell.staleguard.toolwindow.BuildFileRows
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Everything Staleguard can tell about a project, in one run: fresh version
 * and advisory data for every declared dependency, the classpath linkage
 * doctor, and vulnerability reachability, folded into one [CheckupReport].
 *
 * Explicit action only. It may download (version metadata, advisories, fix
 * candidates, fixed releases), all through the same routed and credentialed
 * paths every other feature uses, and nothing runs in the background.
 */
@Service(Service.Level.PROJECT)
class CheckupService(private val project: Project) {

    /** Where advisories come from: the warm OSV cache. A seam only for tests, which must not need osv.dev. */
    internal var advisories: (Coordinates, String) -> List<OsvAdvisory>? = { coordinates, version ->
        VulnerabilityService.getInstance().peek(coordinates, version)?.advisories
    }

    /** Where a finding points in the editor. */
    data class Location(val file: VirtualFile, val offset: Int)

    class Outcome(
        val report: CheckupReport.Report,
        /** The linkage audit, for the full details dialog. */
        val linkage: ClasspathLinkageService.Result,
    )

    fun run(indicator: ProgressIndicator): Outcome {
        // The lookups block cancellably, which needs an indicator bound to
        // this thread; a background task binds one, any other caller gets it here.
        if (ProgressManager.getInstance().progressIndicator == null) {
            return ProgressManager.getInstance().runProcess(Computable { runBound(indicator) }, indicator)
        }
        return runBound(indicator)
    }

    private fun runBound(indicator: ProgressIndicator): Outcome {
        indicator.isIndeterminate = false

        indicator.text = StaleguardBundle.message("checkup.progress.collect")
        var rows = collectRows()

        // Fresh data first: a report from a cold cache would read clean
        // simply because nothing had been looked up yet.
        indicator.text = StaleguardBundle.message("checkup.progress.lookups")
        indicator.fraction = 0.05
        warm(rows, indicator)
        rows = collectRows() // resolved versions can depend on fetched data (properties, BOMs)
        FreshnessRefreshService.getInstance(project).repaintBuildFiles()
        project.messageBus.syncPublisher(FreshnessListener.TOPIC).freshnessChanged()

        indicator.text = StaleguardBundle.message("checkup.progress.linkage")
        indicator.fraction = 0.25
        val linkage = ClasspathLinkageService.getInstance(project).audit(indicator)

        indicator.text = StaleguardBundle.message("checkup.progress.reach")
        indicator.fraction = 0.6
        ReachabilityService.getInstance(project).check(indicator)

        indicator.fraction = 0.95
        val report = CheckupReport.build(inputs(rows, linkage))
        return Outcome(report, linkage)
    }

    private fun collectRows(): List<BuildFileRows.Entry> =
        ReadAction.nonBlocking<List<BuildFileRows.Entry>> { BuildFileRows.collect(project) }
            .inSmartMode(project)
            .executeSynchronously()

    private fun warm(rows: List<BuildFileRows.Entry>, indicator: ProgressIndicator) {
        val coordinates = rows.mapNotNull { row ->
            val declared = row.input.declared
            Coordinates(declared.groupId ?: return@mapNotNull null, declared.artifactId ?: return@mapNotNull null)
        }.distinct()
        val versioned = rows.mapNotNull { row ->
            val declared = row.input.declared
            val version = declared.resolvedVersion ?: return@mapNotNull null
            if (version.endsWith("-SNAPSHOT", ignoreCase = true)) return@mapNotNull null
            VulnKey(Coordinates(declared.groupId ?: return@mapNotNull null, declared.artifactId ?: return@mapNotNull null), version)
        }.distinct()
        val transitive = ReadAction.nonBlocking<List<VulnKey>> {
            val transitive = TransitiveVulnScan.candidates(Provenance.nodesFor(project))
                .map { VulnKey(Coordinates(it.groupId, it.artifactId), it.version) }
            val locked = LockfileScan.collect(project).flatMap { it.locked }
                .map { VulnKey(Coordinates(it.group, it.name), it.version) }
            transitive + locked
        }.inSmartMode(project).executeSynchronously()

        val lookup = VersionLookupService.getInstance()
        val vulnerabilities = VulnerabilityService.getInstance()
        runBlockingCancellable {
            val permits = Semaphore(LOOKUP_CONCURRENCY)
            var done = 0
            coordinates.map { coords ->
                async {
                    permits.withPermit { runCatching { lookup.lookup(coords) } }
                    synchronized(this@CheckupService) {
                        done++
                        indicator.fraction = 0.05 + 0.15 * done / coordinates.size
                    }
                }
            }.awaitAll()
            // One batch request per chunk: OSV's batch endpoint is built for this.
            for (chunk in (versioned + transitive).distinct().chunked(VULN_BATCH)) {
                runCatching { vulnerabilities.lookupBatch(chunk) }
            }
        }
    }

    private fun inputs(rows: List<BuildFileRows.Entry>, linkage: ClasspathLinkageService.Result): CheckupReport.Inputs {
        val settings = StaleguardSettings.getInstance()
        val thresholdMs = TimeUnit.DAYS.toMillis(365L * settings.state.abandonmentYears)
        val now = System.currentTimeMillis()
        val vulnerability = VulnerabilityService.getInstance()
        val reachState = ReachabilityState.getInstance(project)

        return ReadAction.nonBlocking<CheckupReport.Inputs> {
            val policy = com.tampwell.staleguard.policy.ProjectPolicyService.getInstance(project)
            val plannerInputs = rows.map { it.input }
            val plan = UpgradePlanner.plan(
                plannerInputs, settings.state.suggestPrereleases, thresholdMs, policy::isIgnored, now,
                versionAllowed = policy::versionAllowed,
                measuredImpact = com.tampwell.staleguard.impact.ImpactMemory.getInstance(project).lookup(),
            )
            val summary = StatsCalculator.summary(
                StatsCalculator.compute(plannerInputs, plan, thresholdMs, now, vulnerability.advisoryCounter()),
            )

            val vulns = ArrayList<CheckupReport.Vulnerability>()
            val seen = HashSet<String>()
            fun add(
                coordinates: Coordinates,
                version: String,
                advisories: List<OsvAdvisory>,
                declared: Boolean,
                via: String?,
                target: Any?,
            ) {
                if (!seen.add("$coordinates:$version")) return
                if (policy.isIgnored(coordinates.groupId, coordinates.artifactId)) return
                for (advisory in advisories) {
                    val verdict = reachState.verdictFor(coordinates, version, advisory.id)
                    val (reach, detail) = when (verdict) {
                        is ReachVerdict.Reached ->
                            (if (verdict.inProduction) CheckupReport.Reach.REACHED else CheckupReport.Reach.TESTS_ONLY) to
                                verdict.path.joinToString(" -> ")
                        is ReachVerdict.NotReached -> CheckupReport.Reach.NOT_REACHED to null
                        is ReachVerdict.Unknown -> CheckupReport.Reach.UNDETERMINED to ReachMessages.reason(verdict.reason)
                        null -> CheckupReport.Reach.NOT_CHECKED to null
                    }
                    vulns += CheckupReport.Vulnerability(
                        artifact = coordinates.artifactId,
                        version = version,
                        advisory = advisory.displayId,
                        severity = advisory.severity,
                        fixedVersion = advisory.fixedVersion,
                        declared = declared,
                        reach = reach,
                        detail = listOfNotNull(via?.let { "via $it" }, detail).joinToString("; ").ifEmpty { null },
                        target = target,
                    )
                }
            }

            for (row in rows) {
                val declared = row.input.declared
                val coordinates = Coordinates(declared.groupId ?: continue, declared.artifactId ?: continue)
                val version = declared.resolvedVersion ?: continue
                val advisories = advisories(coordinates, version).orEmpty()
                if (advisories.isNotEmpty()) add(coordinates, version, advisories, true, null, Location(row.file, row.offset))
            }
            for (candidate in TransitiveVulnScan.candidates(Provenance.nodesFor(project))) {
                val coordinates = Coordinates(candidate.groupId, candidate.artifactId)
                val advisories = advisories(coordinates, candidate.version).orEmpty()
                if (advisories.isNotEmpty()) add(coordinates, candidate.version, advisories, false, candidate.via, null)
            }
            val lockEntries = LockfileScan.collect(project)
            for (entry in lockEntries) {
                for (line in entry.lines) {
                    val coordinates = Coordinates(line.locked.group, line.locked.name)
                    val advisories = advisories(coordinates, line.locked.version).orEmpty()
                    if (advisories.isNotEmpty()) {
                        add(coordinates, line.locked.version, advisories, false, null, Location(entry.file, line.range.first))
                    }
                }
            }

            // Everything the reachability check found on the real classpaths:
            // the ground truth when a build's resolved tree was not available.
            for ((key, _) in reachState.verdicts.entries.distinctBy { Triple(it.key.groupId, it.key.artifactId, it.key.version) }) {
                val coordinates = Coordinates(key.groupId, key.artifactId)
                val found = advisories(coordinates, key.version).orEmpty()
                if (found.isNotEmpty()) add(coordinates, key.version, found, false, null, null)
            }

            val abandoned = if (!settings.state.abandonmentEnabled) {
                emptyList()
            } else {
                rows.mapNotNull { row ->
                    val released = row.input.known?.newestReleaseAtMillis ?: return@mapNotNull null
                    row.input.declared.coordinate.takeIf { now - released > thresholdMs }
                }.distinct()
            }

            CheckupReport.Inputs(
                vulnerabilities = vulns,
                linkageFailures = linkageLines(linkage),
                shadowedGroups = linkage.report.shadowedGroups.size,
                lockDrifts = LockfileScan.driftsFor(lockEntries, plannerInputs).size,
                abandoned = abandoned,
                majorUpdates = summary.majorUpdates,
                minorUpdates = summary.minorUpdates,
                patchUpdates = summary.patchUpdates,
                dependencies = summary.totalDependencies,
                ownCodeChecked = OwnCodeAudit.mayClaimClean(linkage.ownCode),
            )
        }.inSmartMode(project).executeSynchronously()
    }

    /** One line per pair of jars that disagree, not one per broken call: the pair is what gets fixed. */
    private fun linkageLines(linkage: ClasspathLinkageService.Result): List<String> {
        val members = linkage.report.brokenMembers.groupBy { it.fromJar to it.ownerJar }.map { (pair, refs) ->
            val (from, owner) = pair
            val example = refs.first().ref
            StaleguardBundle.message(
                "checkup.linkage.members",
                from, refs.size, "${example.ownerSimpleName}.${example.name}", owner ?: "?",
            )
        }
        val classes = linkage.report.evictedClasses.groupBy { it.fromJar }.map { (from, evicted) ->
            StaleguardBundle.message("checkup.linkage.classes", from, evicted.size, evicted.first().owner.substringAfterLast('/'))
        }
        return members + classes
    }

    companion object {
        private const val LOOKUP_CONCURRENCY = 8
        private const val VULN_BATCH = 200

        fun getInstance(project: Project): CheckupService = project.service()
    }
}
