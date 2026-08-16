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
            if (settings.isIgnored(declared.group, declared.name)) continue
            val coordinates = Coordinates(declared.group, declared.name)

            val snapshot = lookup.peek(coordinates)
            if (snapshot == null) {
                refresh.requestLookup(coordinates)
                misses++
                continue
            }
            if (snapshot.failed) refresh.requestLookup(coordinates)
            val data = snapshot.value ?: continue

            val current = MavenVersion(declared.version)
            val suggested = VersionSuggestion.suggest(current, data.versions, settings.state.suggestPrereleases)

            if (suggested != null) {
                val severity = UpgradeSeverity.classify(current, suggested)
                if (severity != null) {
                    val releaseAge = data.newestReleaseAtMillis?.let { now - it }
                    val recommendation = Recommendation.of(
                        severity,
                        releaseAge,
                        abandoned = releaseAge != null && releaseAge > abandonmentThresholdMs,
                    )
                    val message = if (releaseAge != null) {
                        StaleguardBundle.message(
                            "inspection.outdated.message",
                            StaleguardBundle.message("severity.${severity.name.lowercase()}"),
                            current.value,
                            suggested.value,
                            StaleguardBundle.message(recommendation.bundleKey),
                            com.tampwell.staleguard.util.RelativeTime.ago(releaseAge),
                        )
                    } else {
                        StaleguardBundle.message(
                            "inspection.outdated.message.noage",
                            StaleguardBundle.message("severity.${severity.name.lowercase()}"),
                            current.value,
                            suggested.value,
                            StaleguardBundle.message(recommendation.bundleKey),
                        )
                    }
                    problems += manager.createProblemDescriptor(
                        declared.anchor,
                        message,
                        isOnTheFly,
                        listOfNotNull(
                            GradleBumpVersionQuickFix(suggested.value, declared.fixMode),
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
    )

    /** External dependencies declared inside any `dependencies { }` closure. */
    private fun collect(file: GroovyFile): List<GradleDeclared> {
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
            // and fall through naturally.
            val literal = call.expressionArguments.firstOrNull() as? GrLiteral ?: continue
            val notation = plainString(literal) ?: continue
            val parsed = GradleNotationParser.parse(notation) ?: continue
            result.add(
                GradleDeclared(parsed.group, parsed.name, parsed.version, literal, GradleBumpVersionQuickFix.Mode.NOTATION),
            )
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
