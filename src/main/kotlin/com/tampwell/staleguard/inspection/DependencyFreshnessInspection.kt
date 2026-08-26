package com.tampwell.staleguard.inspection

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlFile
import com.intellij.openapi.diagnostic.logger
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.maven.PomDependencyCollector
import com.tampwell.staleguard.plan.Recommendation
import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.services.FreshnessRefreshService
import com.tampwell.staleguard.services.VersionLookupService
import com.tampwell.staleguard.settings.StaleguardSettings
import com.tampwell.staleguard.version.MavenVersion
import com.tampwell.staleguard.version.UpgradeSeverity
import com.tampwell.staleguard.version.VersionSuggestion
import java.util.concurrent.TimeUnit
import org.jetbrains.idea.maven.dom.MavenDomUtil

/**
 * The core product surface: flags outdated and abandoned dependencies in
 * pom.xml.
 *
 * ARCHITECTURE INVARIANT (do not weaken): this runs inside highlighting
 * passes, so it must never suspend, fetch, or touch disk. It reads the warm
 * cache via [VersionLookupService.peek] only; unknown coordinates are handed
 * to [FreshnessRefreshService], which re-triggers highlighting when data
 * arrives.
 */
class DependencyFreshnessInspection : LocalInspectionTool() {

    private val log = logger<DependencyFreshnessInspection>()

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (file !is XmlFile || file.name != "pom.xml") return null
        val project = file.project
        val virtualFile = file.virtualFile ?: return null
        val model = MavenDomUtil.getMavenDomProjectModel(project, virtualFile)
        if (model == null) {
            log.info("Staleguard: no Maven DOM model for ${virtualFile.path} — skipping")
            return null
        }

        val settings = StaleguardSettings.getInstance()
        val abandonmentThresholdMs = TimeUnit.DAYS.toMillis(365L * settings.state.abandonmentYears)
        val lookup = VersionLookupService.getInstance()
        val refresh = FreshnessRefreshService.getInstance(project)
        val problems = mutableListOf<ProblemDescriptor>()
        val now = System.currentTimeMillis()
        var misses = 0
        var hits = 0

        for ((dom, declared) in PomDependencyCollector.collectWithDom(model)) {
            val groupId = declared.groupId ?: continue
            val artifactId = declared.artifactId ?: continue
            if (com.tampwell.staleguard.policy.ProjectPolicyService.getInstance(project).isIgnored(groupId, artifactId)) continue
            val coordinates = Coordinates(groupId, artifactId)

            // Snapshot pinning check BEFORE the cache guard: internal
            // -SNAPSHOT artifacts 404 on every repository, so a check placed
            // after resolution would never fire for exactly the deps it is
            // about.
            if (declared.resolvedVersion?.endsWith("-SNAPSHOT", ignoreCase = true) == true) {
                val snapshotAnchor = dom.version.xmlTag ?: dom.xmlTag
                if (snapshotAnchor != null) {
                    problems += manager.createProblemDescriptor(
                        snapshotAnchor,
                        StaleguardBundle.message("inspection.snapshot.message", declared.resolvedVersion),
                        isOnTheFly,
                        arrayOf<com.intellij.codeInspection.LocalQuickFix>(
                            IgnoreDependencyQuickFix(groupId, artifactId),
                        ),
                        ProblemHighlightType.WEAK_WARNING,
                    )
                }
            }

            // --- Known vulnerabilities: independent of freshness — the
            // current version being the newest doesn't make it safe.
            val advisories = VulnerabilityProblems.advisoriesFor(project, coordinates, declared.resolvedVersion)
            if (!advisories.isNullOrEmpty()) {
                val anchor = dom.version.xmlTag ?: dom.xmlTag
                if (anchor != null) {
                    val worst = VulnerabilityProblems.worst(advisories)
                    val target = FixTarget.of(declared.rawVersion)
                    val fixes = listOfNotNull(
                        worst.fixedVersion?.takeIf { target != FixTarget.None }
                            ?.let { BumpVersionQuickFix(it, target) },
                        OpenAdvisoryQuickFix(worst.url, worst.displayId),
                        IgnoreDependencyQuickFix(groupId, artifactId),
                    ).toTypedArray<com.intellij.codeInspection.LocalQuickFix>()
                    problems += manager.createProblemDescriptor(
                        anchor,
                        VulnerabilityProblems.message(advisories),
                        isOnTheFly,
                        fixes,
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    )
                }
            }

            val snapshot = lookup.peek(coordinates)
            if (snapshot == null) {
                // Never resolved this session — resolve in background, repaint later.
                refresh.requestLookup(coordinates)
                misses++
                continue
            }
            if (snapshot.failed) {
                // Last fetch errored — keep asking; the engine throttles real retries.
                refresh.requestLookup(coordinates)
            }
            val data = snapshot.value ?: continue // known absent (404): nothing to say

            // --- License policy: silent unless the project committed [licenses] rules ---
            LicenseProblems.check(project, coordinates.toString(), data.licenses)?.let { finding ->
                val anchor = dom.artifactId.xmlTag ?: dom.xmlTag
                if (anchor != null) {
                    problems += manager.createProblemDescriptor(
                        anchor, finding.message, isOnTheFly,
                        arrayOf<com.intellij.codeInspection.LocalQuickFix>(
                            IgnoreDependencyQuickFix(groupId, artifactId),
                        ),
                        finding.highlight,
                    )
                }
            }

            // --- Freshness ---
            val current = declared.resolvedVersion?.let(::MavenVersion)
            val allowedByPins = { v: MavenVersion ->
                com.tampwell.staleguard.policy.ProjectPolicyService.getInstance(project).versionAllowed(groupId, artifactId, current, v)
            }
            val suggested = VersionSuggestion.suggest(current, data.versions, settings.state.suggestPrereleases, allowedByPins)
            if (current != null && suggested != null) {
                val steered = com.tampwell.staleguard.version.SuggestionSafety.steerClear(
                    current, suggested, data.versions, settings.state.suggestPrereleases, allowedByPins,
                ) { v -> !VulnerabilityProblems.advisoriesFor(project, coordinates, v.value).isNullOrEmpty() }
                val bumpTo = steered.version
                val severity = UpgradeSeverity.classify(current, bumpTo)
                if (severity != null) {
                    val anchor = dom.version.xmlTag ?: dom.xmlTag
                    if (anchor != null) {
                        val target = FixTarget.of(declared.rawVersion)
                        val fixes = listOfNotNull(
                            when (target) {
                                FixTarget.None -> null
                                else -> BumpVersionQuickFix(bumpTo.value, target)
                            },
                            data.scmUrl?.let {
                                ShowChangelogQuickFix(
                                    coordinates.toString(), it, artifactId,
                                    current.value, bumpTo.value, data.versions.map { v -> v.value },
                                )
                            },
                            // A parent POM, an imported BOM and a build plugin have no
                            // compile-classpath binary, so there is nothing to compare.
                            if (declared.origin in IMPACT_ANALYSABLE) {
                                com.tampwell.staleguard.impact.CheckUpgradeImpactQuickFix(
                                    coordinates.toString(), current.value, bumpTo.value,
                                )
                            } else {
                                null
                            },
                            com.tampwell.staleguard.repository.ScmUrls.changelogUrl(data.scmUrl)
                                ?.let(::OpenChangelogQuickFix),
                            IgnoreDependencyQuickFix(groupId, artifactId),
                        ).toTypedArray<com.intellij.codeInspection.LocalQuickFix>()
                        val releaseAge = data.newestReleaseAtMillis?.let { now - it }
                        val recommendation = Recommendation.of(
                            severity,
                            releaseAge,
                            abandoned = releaseAge != null && releaseAge > abandonmentThresholdMs,
                            vulnerable = !advisories.isNullOrEmpty(),
                            ageDrivenStale = declared.origin != com.tampwell.staleguard.model.DeclaredDependency.Origin.BUILD_PLUGIN,
                        )
                        val message = when (declared.origin) {
                            com.tampwell.staleguard.model.DeclaredDependency.Origin.PARENT ->
                                FreshnessProblems.parentMessage(artifactId, current.value, bumpTo.value, recommendation)
                            com.tampwell.staleguard.model.DeclaredDependency.Origin.BOM_IMPORT ->
                                FreshnessProblems.bomMessage(artifactId, current.value, bumpTo.value, recommendation)
                            else ->
                                FreshnessProblems.message(severity, current.value, bumpTo.value, recommendation, releaseAge)
                        } + FreshnessProblems.vulnerableTargetNote(steered.knownVulnerable) +
                            FreshnessProblems.measuredImpactNote(
                                com.tampwell.staleguard.impact.ImpactMemory.getInstance(project)
                                    .measured(coordinates.toString(), current.value, bumpTo.value),
                            )
                        problems += manager.createProblemDescriptor(
                            anchor, message, isOnTheFly, fixes, highlightTypeFor(severity),
                        )
                    }
                }
            }

            // --- Abandonment: independent of freshness, per product decision.
            // Build plugins are exempt: core Maven plugins routinely go years
            // between releases while being perfectly healthy — flagging
            // maven-clean-plugin as abandoned would just teach users to
            // ignore the warning.
            val newestReleaseAt = data.newestReleaseAtMillis
            if (settings.state.abandonmentEnabled &&
                declared.origin != com.tampwell.staleguard.model.DeclaredDependency.Origin.BUILD_PLUGIN &&
                newestReleaseAt != null && now - newestReleaseAt > abandonmentThresholdMs
            ) {
                val anchor = dom.artifactId.xmlTag ?: dom.xmlTag
                if (anchor != null) {
                    problems += manager.createProblemDescriptor(
                        anchor,
                        StaleguardBundle.message(
                            "inspection.abandoned.message",
                            coordinates.toString(),
                            com.tampwell.staleguard.util.RelativeTime.monthYear(newestReleaseAt),
                            com.tampwell.staleguard.util.RelativeTime.ago(now - newestReleaseAt),
                        ),
                        isOnTheFly,
                        arrayOf<com.intellij.codeInspection.LocalQuickFix>(
                            IgnoreDependencyQuickFix(groupId, artifactId),
                        ),
                        ProblemHighlightType.WEAK_WARNING,
                    )
                }
            }
            hits++
        }

        log.info(
            "Staleguard: checked ${virtualFile.name}: ${problems.size} problem(s), " +
                "$hits cache hit(s), $misses miss(es)",
        )
        return problems.toTypedArray()
    }

    companion object {

        /** Origins that resolve to a jar on the compile classpath, the only ones a binary diff can speak about. */
        private val IMPACT_ANALYSABLE = setOf(
            com.tampwell.staleguard.model.DeclaredDependency.Origin.DEPENDENCIES,
            com.tampwell.staleguard.model.DeclaredDependency.Origin.DEPENDENCY_MANAGEMENT,
        )

        /**
         * MAJOR is deliberately the WEAKER highlight: a major bump is the
         * riskiest to apply, so it nudges rather than nags. MINOR/PATCH are
         * safe upgrades and get the standard warning. Pure function, tested.
         */
        fun highlightTypeFor(severity: UpgradeSeverity): ProblemHighlightType = when (severity) {
            UpgradeSeverity.MAJOR -> ProblemHighlightType.WEAK_WARNING
            UpgradeSeverity.MINOR -> ProblemHighlightType.GENERIC_ERROR_OR_WARNING
            UpgradeSeverity.PATCH -> ProblemHighlightType.GENERIC_ERROR_OR_WARNING
            UpgradeSeverity.QUALIFIER -> ProblemHighlightType.WEAK_WARNING
        }
    }
}
