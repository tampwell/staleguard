package com.tampwell.staleguard.gradle

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
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
import com.tampwell.staleguard.version.isStable
import java.util.concurrent.TimeUnit
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

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

        val catalogFile = findCatalogFile(file.virtualFile)
        val catalog = catalogFile
            ?.let { runCatching { VersionCatalog.parse(String(it.contentsToByteArray())) }.getOrNull() }
            ?: VersionCatalog.EMPTY

        for (declared in collect(file, catalog, catalogFile)) {
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
            val suggested =
                if (settings.state.suggestPrereleases) data.latest
                else data.versions.filter { it.isStable }.maxOrNull()

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
                        declared.anchor,
                        message,
                        isOnTheFly,
                        listOfNotNull(
                            declared.fix(suggested.value),
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

    private class KtsDeclared(
        val group: String,
        val name: String,
        val version: String,
        val anchor: PsiElement,
        val fixFactory: (String) -> LocalQuickFix?,
    ) {
        fun fix(newVersion: String): LocalQuickFix? = fixFactory(newVersion)
    }

    private fun collect(
        file: KtFile,
        catalog: VersionCatalog.Parsed,
        catalogFile: VirtualFile?,
    ): List<KtsDeclared> {
        val result = mutableListOf<KtsDeclared>()
        for (call in PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java)) {
            if (!isInsideDependenciesBlock(call)) continue

            val arguments = call.valueArguments
            if (arguments.isEmpty()) continue

            // Named-argument notation: implementation(group = "g", name = "n", version = "v")
            val named = arguments.mapNotNull { arg ->
                arg.getArgumentName()?.asName?.asString()?.let { it to arg.getArgumentExpression() }
            }.toMap()
            if (named.isNotEmpty()) {
                val group = plainString(named["group"]) ?: continue
                val name = plainString(named["name"]) ?: continue
                val versionExpr = named["version"] as? KtStringTemplateExpression ?: continue
                val version = plainString(versionExpr) ?: continue
                result.add(
                    KtsDeclared(group, name, version, versionExpr) { newVersion ->
                        BumpKtsVersionQuickFix(newVersion, BumpKtsVersionQuickFix.Mode.WHOLE_LITERAL)
                    },
                )
                continue
            }

            when (val argument = arguments.first().getArgumentExpression()) {
                // String notation: implementation("g:a:v")
                is KtStringTemplateExpression -> {
                    val notation = plainString(argument) ?: continue
                    val parsed = GradleNotationParser.parse(notation) ?: continue
                    result.add(
                        KtsDeclared(parsed.group, parsed.name, parsed.version, argument) { newVersion ->
                            BumpKtsVersionQuickFix(newVersion, BumpKtsVersionQuickFix.Mode.NOTATION)
                        },
                    )
                }

                // Catalog reference: implementation(libs.gson)
                is KtDotQualifiedExpression -> {
                    val text = argument.text
                    if (!text.startsWith("libs.")) continue
                    val resolved = catalog.resolve(text.removePrefix("libs.")) ?: continue
                    result.add(
                        KtsDeclared(resolved.group, resolved.name, resolved.version, argument) { newVersion ->
                            val versionKey = resolved.versionKey
                            if (versionKey != null && catalogFile != null) {
                                UpdateCatalogVersionQuickFix(
                                    versionKey, newVersion, catalog.referenceCount(versionKey), catalogFile.path,
                                )
                            } else {
                                null // inline catalog version: report-only in v1
                            }
                        },
                    )
                }

                else -> Unit // project(...), platform(...), files(...): not external literals
            }
        }
        return result
    }

    /** Literal string with zero interpolation, or null. */
    private fun plainString(expression: PsiElement?): String? {
        val template = expression as? KtStringTemplateExpression ?: return null
        if (template.entries.any { it !is KtLiteralStringTemplateEntry }) return null
        return template.entries.joinToString("") { it.text }
    }

    private fun isInsideDependenciesBlock(call: KtCallExpression): Boolean {
        val callee = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return false
        if (callee == "dependencies") return false
        val lambda = PsiTreeUtil.getParentOfType(call, KtLambdaExpression::class.java) ?: return false
        val owner = PsiTreeUtil.getParentOfType(lambda, KtCallExpression::class.java) ?: return false
        return (owner.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() == "dependencies"
    }

    /** Walks up from the build file looking for gradle/libs.versions.toml. */
    private fun findCatalogFile(buildFile: VirtualFile?): VirtualFile? {
        var dir = buildFile?.parent
        var depth = 0
        while (dir != null && depth < 6) {
            dir.findChild("gradle")?.findChild("libs.versions.toml")?.let { return it }
            dir = dir.parent
            depth++
        }
        return null
    }
}
