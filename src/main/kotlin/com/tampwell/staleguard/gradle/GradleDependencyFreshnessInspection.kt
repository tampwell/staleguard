package com.tampwell.staleguard.gradle

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.inspection.DependencyFreshnessInspection
import com.tampwell.staleguard.plan.Recommendation
import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.services.FreshnessRefreshService
import com.tampwell.staleguard.services.VersionLookupService
import com.tampwell.staleguard.settings.StaleguardSettings
import com.tampwell.staleguard.version.MavenVersion
import com.tampwell.staleguard.version.UpgradeSeverity
import com.tampwell.staleguard.version.VersionSuggestion
import java.util.concurrent.TimeUnit
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrClosableBlock
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrMethodCall
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.literals.GrLiteral
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.literals.GrString

/**
 * Gradle Groovy DSL freshness inspection: `dependencies { }` blocks in
 * build.gradle, string notation (`implementation 'g:a:v'`) and map notation
 * (`implementation group: 'g', name: 'a', version: 'v'`). Registered only
 * when the Groovy plugin is present (optional dependency).
 *
 * Same architecture invariant as the Maven inspection: warm-cache reads only.
 * Kotlin DSL and version catalogs are separate milestones. Interpolated
 * versions (GStrings) are skipped — Gradle properties are a later feature.
 */
class GradleDependencyFreshnessInspection : LocalInspectionTool() {

    private val log = logger<GradleDependencyFreshnessInspection>()

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (file !is GroovyFile || file.name != "build.gradle") return null
        val project = file.project

        val settings = StaleguardSettings.getInstance()
        val abandonmentThresholdMs = TimeUnit.DAYS.toMillis(365L * settings.state.abandonmentYears)
        val lookup = VersionLookupService.getInstance()
        val refresh = FreshnessRefreshService.getInstance(project)
        val problems = mutableListOf<ProblemDescriptor>()
        val now = System.currentTimeMillis()
        var misses = 0

        for (declared in collect(file)) {
            if (com.tampwell.staleguard.policy.ProjectPolicyService.getInstance(project).isIgnored(declared.group, declared.name)) continue
            val coordinates = Coordinates(declared.group, declared.name)

            // Before the cache guard — internal snapshots never resolve.
            if (declared.version.endsWith("-SNAPSHOT", ignoreCase = true)) {
                problems += manager.createProblemDescriptor(
                    declared.anchor,
                    StaleguardBundle.message("inspection.snapshot.message", declared.version),
                    isOnTheFly,
                    arrayOf<com.intellij.codeInspection.LocalQuickFix>(
                        com.tampwell.staleguard.inspection.IgnoreDependencyQuickFix(declared.group, declared.name),
                    ),
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
                        worst.fixedVersion?.let { declared.bumpFix(it) },
                        com.tampwell.staleguard.inspection.OpenAdvisoryQuickFix(worst.url, worst.displayId),
                        com.tampwell.staleguard.inspection.IgnoreDependencyQuickFix(declared.group, declared.name),
                    ).toTypedArray<com.intellij.codeInspection.LocalQuickFix>(),
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
                        arrayOf<com.intellij.codeInspection.LocalQuickFix>(
                            com.tampwell.staleguard.inspection.IgnoreDependencyQuickFix(declared.group, declared.name),
                        ),
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
                    } + com.tampwell.staleguard.inspection.FreshnessProblems.vulnerableTargetNote(steered?.knownVulnerable == true)
                    problems += manager.createProblemDescriptor(
                        declared.anchor,
                        message,
                        isOnTheFly,
                        listOfNotNull(
                            declared.bumpFix(suggested.value),
                            data.scmUrl?.let {
                                com.tampwell.staleguard.inspection.ShowChangelogQuickFix(
                                    coordinates.toString(), it, declared.name,
                                    current.value, suggested.value, data.versions.map { v -> v.value },
                                )
                            },
                            com.tampwell.staleguard.repository.ScmUrls.changelogUrl(data.scmUrl)
                                ?.let { com.tampwell.staleguard.inspection.OpenChangelogQuickFix(it) },
                            com.tampwell.staleguard.inspection.IgnoreDependencyQuickFix(declared.group, declared.name),
                        ).toTypedArray<com.intellij.codeInspection.LocalQuickFix>(),
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
                        com.tampwell.staleguard.util.RelativeTime.monthYear(newestReleaseAt),
                        com.tampwell.staleguard.util.RelativeTime.ago(now - newestReleaseAt),
                    ),
                    isOnTheFly,
                    arrayOf<com.intellij.codeInspection.LocalQuickFix>(
                        com.tampwell.staleguard.inspection.IgnoreDependencyQuickFix(declared.group, declared.name),
                    ),
                    ProblemHighlightType.WEAK_WARNING,
                )
            }
        }

        log.info("Staleguard: checked ${file.name}: ${problems.size} problem(s), $misses cache miss(es)")
        return problems.toTypedArray()
    }

    private data class GradleDeclared(
        val group: String,
        val name: String,
        val version: String,
        val anchor: GrLiteral,
        val fixMode: GradleBumpVersionQuickFix.Mode,
        /** True when this literal sits inside platform()/enforcedPlatform(). */
        val isPlatform: Boolean = false,
        /** Set when the version comes from gradle.properties — fixes edit there. */
        val propertyKey: String? = null,
        val propertiesPath: String? = null,
    ) {
        fun bumpFix(newVersion: String): com.intellij.codeInspection.LocalQuickFix =
            if (propertyKey != null && propertiesPath != null) {
                UpdateGradlePropertyQuickFix(propertyKey, newVersion, propertiesPath)
            } else {
                GradleBumpVersionQuickFix(newVersion, fixMode)
            }
    }

    /** `"g:a:${'$'}{prop}"` / `"g:a:${'$'}prop"` — one simple property in version position. */
    private val INTERPOLATED_NOTATION =
        Regex("""^"([A-Za-z0-9_.\-]+:[A-Za-z0-9_.\-]+):\$(?:\{([A-Za-z0-9_.]+)}|([A-Za-z0-9_.]+))"$""")

    /** External dependencies declared inside any `dependencies { }` closure. */
    private fun collect(file: GroovyFile): List<GradleDeclared> {
        val propertiesFile = GradleProperties.findFile(file.virtualFile)
        val gradleProperties = propertiesFile
            ?.let { runCatching { GradleProperties.parse(String(it.contentsToByteArray())) }.getOrNull() }
            .orEmpty()
        val result = mutableListOf<GradleDeclared>()
        for (call in PsiTreeUtil.findChildrenOfType(file, GrMethodCall::class.java)) {
            if (!isInsideDependenciesBlock(call)) continue

            val named = call.namedArguments
            if (named.isNotEmpty()) {
                fun literalOf(label: String): GrLiteral? = named
                    .firstOrNull { it.labelName == label }
                    ?.expression as? GrLiteral

                val group = plainString(literalOf("group")) ?: continue
                val name = plainString(literalOf("name")) ?: continue
                val versionLiteral = literalOf("version") ?: continue
                val version = plainString(versionLiteral) ?: continue
                result.add(
                    GradleDeclared(group, name, version, versionLiteral, GradleBumpVersionQuickFix.Mode.MAP_VERSION),
                )
                continue
            }

            // String notation: first expression argument that is a plain literal.
            // project(...), files(...), libs.* references etc. are not literals
            // and fall through naturally. Nested platform('g:a:v') calls are
            // iterated on their own here, which is exactly how they get found —
            // the invoked name is what marks them as a BOM import.
            val literal = call.expressionArguments.firstOrNull() as? GrLiteral ?: continue
            val isPlatform = call.invokedExpression.text in setOf("platform", "enforcedPlatform")
            val notation = plainString(literal)
            if (notation != null) {
                val parsed = GradleNotationParser.parse(notation) ?: continue
                result.add(
                    GradleDeclared(parsed.group, parsed.name, parsed.version, literal, GradleBumpVersionQuickFix.Mode.NOTATION, isPlatform),
                )
                continue
            }
            // GStrings with one simple property in version position resolve
            // from gradle.properties; anything more expressive stays skipped.
            if (literal is GrString && propertiesFile != null) {
                val match = INTERPOLATED_NOTATION.matchEntire(literal.text) ?: continue
                val key = match.groupValues[2].ifEmpty { match.groupValues[3] }
                val version = gradleProperties[key] ?: continue
                val coordinate = match.groupValues[1].split(':')
                result.add(
                    GradleDeclared(
                        coordinate[0], coordinate[1], version, literal,
                        GradleBumpVersionQuickFix.Mode.NOTATION, isPlatform,
                        propertyKey = key, propertiesPath = propertiesFile.path,
                    ),
                )
            }
        }
        return result
    }

    /** String value of a literal, rejecting GStrings with interpolation. */
    private fun plainString(literal: GrLiteral?): String? {
        if (literal == null || literal is GrString) return null
        return literal.value as? String
    }

    private fun isInsideDependenciesBlock(call: GrMethodCall): Boolean {
        if (call.invokedExpression.text == "dependencies") return false
        val closure = PsiTreeUtil.getParentOfType(call, GrClosableBlock::class.java) ?: return false
        val owner = PsiTreeUtil.getParentOfType(closure, GrMethodCall::class.java) ?: return false
        return owner.invokedExpression.text == "dependencies"
    }
}
