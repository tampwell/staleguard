package com.tampwell.staleguard.toml

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.gradle.VersionCatalog
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
import org.toml.lang.psi.TomlKeyValue
import org.toml.lang.psi.TomlLiteral
import org.toml.lang.psi.TomlTable

/**
 * Freshness inspection inside the version catalog itself (*.versions.toml) —
 * where Gradle and Android projects actually edit versions. Flags stale
 * `[versions]` keys (through the libraries and plugins that reference them),
 * inline library versions, and `[plugins]` versions via their Plugin Portal
 * marker artifacts. Registered only when the TOML plugin is present.
 *
 * Same warm-cache invariant as every other Staleguard inspection: peek() only,
 * misses are enqueued and the file repaints on the freshness event.
 */
class TomlCatalogFreshnessInspection : LocalInspectionTool() {

    private val log = logger<TomlCatalogFreshnessInspection>()

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (!file.name.endsWith(".versions.toml")) return null
        val catalog = VersionCatalog.parse(file.text)
        if (catalog.isEmpty) return null

        val settings = StaleguardSettings.getInstance()
        val abandonmentThresholdMs = TimeUnit.DAYS.toMillis(365L * settings.state.abandonmentYears)
        val lookup = VersionLookupService.getInstance()
        val refresh = FreshnessRefreshService.getInstance(file.project)
        val problems = mutableListOf<ProblemDescriptor>()
        val now = System.currentTimeMillis()
        var misses = 0

        for (table in PsiTreeUtil.findChildrenOfType(file, TomlTable::class.java)) {
            val entries = table.entries
            when (table.header.key?.text) {
                "versions" -> entries.forEach { entry ->
                    checkVersionKey(entry, catalog)?.let { check(it, manager, isOnTheFly, settings, lookup, refresh, abandonmentThresholdMs, now, problems) { misses++ } }
                }

                "libraries" -> entries.forEach { entry ->
                    checkInlineLibrary(entry, catalog)?.let { check(it, manager, isOnTheFly, settings, lookup, refresh, abandonmentThresholdMs, now, problems) { misses++ } }
                }

                "plugins" -> entries.forEach { entry ->
                    checkInlinePlugin(entry, catalog)?.let { check(it, manager, isOnTheFly, settings, lookup, refresh, abandonmentThresholdMs, now, problems) { misses++ } }
                }
            }
        }

        log.info("Staleguard: checked ${file.name}: ${problems.size} problem(s), $misses cache miss(es)")
        return problems.toTypedArray()
    }

    private class Checkable(
        val coordinates: Coordinates,
        val version: String,
        val anchor: PsiElement,
        /** Set for [versions] entries — enables the blast-radius confirmation. */
        val versionKey: String?,
        val referenceCount: Int,
        /** Bump fix only when the anchor is a plain quoted literal we can rewrite. */
        val fixable: Boolean,
    )

    /**
     * A `[versions]` entry is checked through whichever library or plugin
     * references it — the key itself has no coordinates.
     */
    private fun checkVersionKey(entry: TomlKeyValue, catalog: VersionCatalog.Parsed): Checkable? {
        val key = entry.key.text ?: return null
        val version = catalog.versions[key] ?: return null
        val library = catalog.libraries.values.firstOrNull { it.versionRef == key }
        val coordinates = if (library != null) {
            Coordinates(library.group, library.name)
        } else {
            val plugin = catalog.plugins.values.firstOrNull { it.versionRef == key } ?: return null
            val (group, artifact) = plugin.markerCoordinates
            Coordinates(group, artifact)
        }
        val value = entry.value
        return Checkable(
            coordinates = coordinates,
            version = version,
            anchor = value ?: entry,
            versionKey = key,
            referenceCount = catalog.referenceCount(key),
            fixable = value is TomlLiteral && value.text.startsWith("\""),
        )
    }

    private fun checkInlineLibrary(entry: TomlKeyValue, catalog: VersionCatalog.Parsed): Checkable? {
        val key = entry.key.text ?: return null
        val library = catalog.libraries[key] ?: return null
        val version = library.versionLiteral ?: return null
        return Checkable(
            coordinates = Coordinates(library.group, library.name),
            version = version,
            anchor = entry.value ?: entry,
            versionKey = null,
            referenceCount = 1,
            // Inline-table and shorthand forms are flagged but fixed by hand —
            // rewriting inside `{ ... }` or `g:a:v` strings is a later step.
            fixable = false,
        )
    }

    private fun checkInlinePlugin(entry: TomlKeyValue, catalog: VersionCatalog.Parsed): Checkable? {
        val key = entry.key.text ?: return null
        val plugin = catalog.plugins[key] ?: return null
        val version = plugin.versionLiteral ?: return null
        val (group, artifact) = plugin.markerCoordinates
        return Checkable(
            coordinates = Coordinates(group, artifact),
            version = version,
            anchor = entry.value ?: entry,
            versionKey = null,
            referenceCount = 1,
            fixable = false,
        )
    }

    private fun check(
        checkable: Checkable,
        manager: InspectionManager,
        isOnTheFly: Boolean,
        settings: StaleguardSettings,
        lookup: VersionLookupService,
        refresh: FreshnessRefreshService,
        abandonmentThresholdMs: Long,
        now: Long,
        problems: MutableList<ProblemDescriptor>,
        onMiss: () -> Unit,
    ) {
        val coordinates = checkable.coordinates
        val policy = com.tampwell.staleguard.policy.ProjectPolicyService.getInstance(manager.project)
        if (policy.isIgnored(coordinates.groupId, coordinates.artifactId)) return

        // Before the cache guard — internal snapshots never resolve.
        if (checkable.version.endsWith("-SNAPSHOT", ignoreCase = true)) {
            problems += manager.createProblemDescriptor(
                checkable.anchor,
                StaleguardBundle.message("inspection.snapshot.message", checkable.version),
                isOnTheFly,
                arrayOf<LocalQuickFix>(IgnoreDependencyQuickFix(coordinates.groupId, coordinates.artifactId)),
                ProblemHighlightType.WEAK_WARNING,
            )
        }

        val advisories = com.tampwell.staleguard.inspection.VulnerabilityProblems
            .advisoriesFor(manager.project, coordinates, checkable.version)
        if (!advisories.isNullOrEmpty()) {
            val worst = com.tampwell.staleguard.inspection.VulnerabilityProblems.worst(advisories)
            problems += manager.createProblemDescriptor(
                checkable.anchor,
                com.tampwell.staleguard.inspection.VulnerabilityProblems.message(advisories),
                isOnTheFly,
                listOfNotNull(
                    worst.fixedVersion?.takeIf { checkable.fixable }
                        ?.let { BumpTomlVersionQuickFix(it, checkable.referenceCount, checkable.versionKey) },
                    com.tampwell.staleguard.inspection.OpenAdvisoryQuickFix(worst.url, worst.displayId),
                    IgnoreDependencyQuickFix(coordinates.groupId, coordinates.artifactId),
                ).toTypedArray<LocalQuickFix>(),
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
            )
        }

        val snapshot = lookup.peek(coordinates)
        if (snapshot == null) {
            refresh.requestLookup(coordinates)
            onMiss()
            return
        }
        if (snapshot.failed) refresh.requestLookup(coordinates)
        val data = snapshot.value ?: return

        com.tampwell.staleguard.inspection.LicenseProblems
            .check(manager.project, coordinates.toString(), data.licenses)?.let { finding ->
                problems += manager.createProblemDescriptor(
                    checkable.anchor, finding.message, isOnTheFly,
                    arrayOf<LocalQuickFix>(IgnoreDependencyQuickFix(coordinates.groupId, coordinates.artifactId)),
                    finding.highlight,
                )
            }

        val current = MavenVersion(checkable.version)
        val suggested = VersionSuggestion.suggest(current, data.versions, settings.state.suggestPrereleases)

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
                val message = if (releaseAge != null) {
                    StaleguardBundle.message(
                        "inspection.outdated.message",
                        StaleguardBundle.message("severity.${severity.name.lowercase()}"),
                        current.value, suggested.value,
                        StaleguardBundle.message(recommendation.bundleKey),
                        RelativeTime.ago(releaseAge),
                    )
                } else {
                    StaleguardBundle.message(
                        "inspection.outdated.message.noage",
                        StaleguardBundle.message("severity.${severity.name.lowercase()}"),
                        current.value, suggested.value,
                        StaleguardBundle.message(recommendation.bundleKey),
                    )
                }
                problems += manager.createProblemDescriptor(
                    checkable.anchor,
                    message,
                    isOnTheFly,
                    listOfNotNull(
                        if (checkable.fixable) {
                            BumpTomlVersionQuickFix(suggested.value, checkable.referenceCount, checkable.versionKey)
                        } else {
                            null
                        },
                        data.scmUrl?.let {
                            com.tampwell.staleguard.inspection.ShowChangelogQuickFix(
                                coordinates.toString(), it, coordinates.artifactId,
                                current.value, suggested.value, data.versions.map { v -> v.value },
                            )
                        },
                        ScmUrls.changelogUrl(data.scmUrl)?.let(::OpenChangelogQuickFix),
                        IgnoreDependencyQuickFix(coordinates.groupId, coordinates.artifactId),
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
                checkable.anchor,
                StaleguardBundle.message(
                    "inspection.abandoned.message",
                    coordinates.toString(),
                    RelativeTime.monthYear(newestReleaseAt),
                    RelativeTime.ago(now - newestReleaseAt),
                ),
                isOnTheFly,
                arrayOf<LocalQuickFix>(IgnoreDependencyQuickFix(coordinates.groupId, coordinates.artifactId)),
                ProblemHighlightType.WEAK_WARNING,
            )
        }
    }
}
