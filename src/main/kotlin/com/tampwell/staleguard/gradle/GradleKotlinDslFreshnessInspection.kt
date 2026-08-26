package com.tampwell.staleguard.gradle

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiFile
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.inspection.DependencyFreshnessInspection
import com.tampwell.staleguard.inspection.IgnoreDependencyQuickFix
import com.tampwell.staleguard.inspection.OpenChangelogQuickFix
import com.tampwell.staleguard.plan.Recommendation
import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.repository.ScmUrls
import com.tampwell.staleguard.services.FreshnessRefreshService
import com.tampwell.staleguard.services.VersionLookupService
import com.tampwell.staleguard.settings.StaleguardSettings
import com.tampwell.staleguard.util.RelativeTime
import com.tampwell.staleguard.version.MavenVersion
import com.tampwell.staleguard.version.UpgradeSeverity
import com.tampwell.staleguard.version.VersionSuggestion
import java.util.concurrent.TimeUnit
import org.jetbrains.kotlin.psi.KtFile

/**
 * Gradle Kotlin DSL freshness inspection: `dependencies { }` blocks in
 * build.gradle.kts — plain string notation, named-argument notation, and
 * version-catalog references (`libs.foo`, resolved via gradle/
 * libs.versions.toml). Registered only when the Kotlin plugin is present
 * (optional dependency).
 *
 * Same warm-cache invariant as every other Staleguard inspection. String
 * templates with interpolation (`"${'$'}{Versions.gson}"`) are skipped —
 * buildSrc constant resolution is a documented later milestone.
 */
class GradleKotlinDslFreshnessInspection : LocalInspectionTool() {

    private val log = logger<GradleKotlinDslFreshnessInspection>()

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (file !is KtFile || !file.name.endsWith(".gradle.kts")) return null
        val project = file.project

        val settings = StaleguardSettings.getInstance()
        val abandonmentThresholdMs = TimeUnit.DAYS.toMillis(365L * settings.state.abandonmentYears)
        val lookup = VersionLookupService.getInstance()
        val refresh = FreshnessRefreshService.getInstance(project)
        val problems = mutableListOf<ProblemDescriptor>()
        val now = System.currentTimeMillis()
        var misses = 0

        val catalogFile = KtsDependencyCollector.findCatalogFile(file.virtualFile)
        val catalog = catalogFile
            ?.let { runCatching { VersionCatalog.parse(String(it.contentsToByteArray())) }.getOrNull() }
            ?: VersionCatalog.EMPTY
        val propertiesFile = GradleProperties.findFile(file.virtualFile)
        val gradleProperties = propertiesFile
            ?.let { runCatching { GradleProperties.parse(String(it.contentsToByteArray())) }.getOrNull() }
            .orEmpty() + runCatching { BuildSrcVersions.find(file.virtualFile) }.getOrDefault(emptyMap())

        for (declared in KtsDependencyCollector.collect(file, catalog, catalogFile, gradleProperties, propertiesFile?.path)) {
            if (com.tampwell.staleguard.policy.ProjectPolicyService.getInstance(project).isIgnored(declared.group, declared.name)) continue
            val coordinates = Coordinates(declared.group, declared.name)

            // Before the cache guard — internal snapshots never resolve.
            if (declared.version.endsWith("-SNAPSHOT", ignoreCase = true)) {
                problems += manager.createProblemDescriptor(
                    declared.anchor,
                    StaleguardBundle.message("inspection.snapshot.message", declared.version),
                    isOnTheFly,
                    arrayOf<LocalQuickFix>(IgnoreDependencyQuickFix(declared.group, declared.name)),
                    ProblemHighlightType.WEAK_WARNING,
                )
            }

            val advisories = com.tampwell.staleguard.inspection.VulnerabilityProblems
                .advisoriesFor(project, coordinates, declared.version)
            if (!advisories.isNullOrEmpty()) {
                val worst = com.tampwell.staleguard.inspection.VulnerabilityProblems.worst(advisories)
                problems += manager.createProblemDescriptor(
                    declared.anchor,
                    com.tampwell.staleguard.inspection.VulnerabilityProblems.message(advisories),
                    isOnTheFly,
                    listOfNotNull(
                        worst.fixedVersion?.let { declared.fix(it) },
                        com.tampwell.staleguard.inspection.OpenAdvisoryQuickFix(worst.url, worst.displayId),
                        IgnoreDependencyQuickFix(declared.group, declared.name),
                    ).toTypedArray<LocalQuickFix>(),
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                )
            }

            val snapshot = lookup.peek(coordinates)
            if (snapshot == null) {
                refresh.requestLookup(coordinates)
                misses++
                continue
            }
            if (snapshot.failed) refresh.requestLookup(coordinates)
            val data = snapshot.value ?: continue

            com.tampwell.staleguard.inspection.LicenseProblems
                .check(project, coordinates.toString(), data.licenses)?.let { finding ->
                    problems += manager.createProblemDescriptor(
                        declared.anchor, finding.message, isOnTheFly,
                        arrayOf<LocalQuickFix>(IgnoreDependencyQuickFix(declared.group, declared.name)),
                        finding.highlight,
                    )
                }

            val current = MavenVersion(declared.version)
            val allowedByPins = { v: MavenVersion ->
                com.tampwell.staleguard.policy.ProjectPolicyService.getInstance(project).versionAllowed(declared.group, declared.name, current, v)
            }
            val rawSuggested = VersionSuggestion.suggest(current, data.versions, settings.state.suggestPrereleases, allowedByPins)

            val steered = rawSuggested?.let {
                com.tampwell.staleguard.version.SuggestionSafety.steerClear(
                    current, it, data.versions, settings.state.suggestPrereleases, allowedByPins,
                ) { v -> !com.tampwell.staleguard.inspection.VulnerabilityProblems.advisoriesFor(project, coordinates, v.value).isNullOrEmpty() }
            }
            val suggested = steered?.version
            if (suggested != null) {
                val severity = UpgradeSeverity.classify(current, suggested)
                if (severity != null) {
                    val releaseAge = data.newestReleaseAtMillis?.let { now - it }
                    val recommendation = Recommendation.of(
                        severity,
                        releaseAge,
                        abandoned = releaseAge != null && releaseAge > abandonmentThresholdMs,
                        vulnerable = !advisories.isNullOrEmpty(),
                    )
                    val message = if (declared.isPlatform) {
                        com.tampwell.staleguard.inspection.FreshnessProblems
                            .bomMessage(declared.name, current.value, suggested.value, recommendation)
                    } else {
                        com.tampwell.staleguard.inspection.FreshnessProblems
                            .message(severity, current.value, suggested.value, recommendation, releaseAge)
                    } + com.tampwell.staleguard.inspection.FreshnessProblems.vulnerableTargetNote(steered?.knownVulnerable == true) +
                        com.tampwell.staleguard.inspection.FreshnessProblems.measuredImpactNote(
                            com.tampwell.staleguard.impact.ImpactMemory.getInstance(project)
                                .measured(coordinates.toString(), current.value, suggested.value),
                        )
                    problems += manager.createProblemDescriptor(
                        declared.anchor,
                        message,
                        isOnTheFly,
                        listOfNotNull(
                            declared.fix(suggested.value),
                            data.scmUrl?.let {
                                com.tampwell.staleguard.inspection.ShowChangelogQuickFix(
                                    coordinates.toString(), it, declared.name,
                                    current.value, suggested.value, data.versions.map { v -> v.value },
                                )
                            },
                            com.tampwell.staleguard.impact.CheckUpgradeImpactQuickFix(
                                coordinates.toString(), current.value, suggested.value,
                            ).takeUnless { coordinates.artifactId.endsWith(".gradle.plugin") },
                            ScmUrls.changelogUrl(data.scmUrl)?.let(::OpenChangelogQuickFix),
                            IgnoreDependencyQuickFix(declared.group, declared.name),
                        ).toTypedArray<LocalQuickFix>(),
                        DependencyFreshnessInspection.highlightTypeFor(severity),
                    )
                }
            }

            val newestReleaseAt = data.newestReleaseAtMillis
            if (settings.state.abandonmentEnabled &&
                newestReleaseAt != null && now - newestReleaseAt > abandonmentThresholdMs
            ) {
                problems += manager.createProblemDescriptor(
                    declared.anchor,
                    StaleguardBundle.message(
                        "inspection.abandoned.message",
                        coordinates.toString(),
                        RelativeTime.monthYear(newestReleaseAt),
                        RelativeTime.ago(now - newestReleaseAt),
                    ),
                    isOnTheFly,
                    arrayOf<LocalQuickFix>(IgnoreDependencyQuickFix(declared.group, declared.name)),
                    ProblemHighlightType.WEAK_WARNING,
                )
            }
        }

        log.info("Staleguard: checked ${file.name}: ${problems.size} problem(s), $misses cache miss(es)")
        return problems.toTypedArray()
    }
}
