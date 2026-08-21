package com.tampwell.staleguard.gradle

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * PSI walking for Kotlin DSL `dependencies { }` blocks — extracted from the
 * inspection so it is platform-testable in isolation (the one deliberately
 * allowed heavyweight test: kts PSI behavior is complex and underdocumented).
 */
internal class KtsDeclared(
    val group: String,
    val name: String,
    val version: String,
    val anchor: PsiElement,
    /** True for platform()/enforcedPlatform() BOM imports — they get the "one edit" message. */
    val isPlatform: Boolean = false,
    private val fixFactory: (String) -> LocalQuickFix?,
) {
    fun fix(newVersion: String): LocalQuickFix? = fixFactory(newVersion)
}

internal object KtsDependencyCollector {

    private val WRAPPER_CALLS = setOf("platform", "enforcedPlatform", "kotlin")

    fun collect(
        file: KtFile,
        catalog: VersionCatalog.Parsed,
        catalogFile: VirtualFile?,
    ): List<KtsDeclared> {
        val result = mutableListOf<KtsDeclared>()
        for (call in PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java)) {
            if (!isInsideDependenciesBlock(call)) continue
            // Wrapper invocations are reached through their configuration call
            // below; visiting them directly would double-report the notation.
            val calleeName = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
            if (calleeName in WRAPPER_CALLS) continue

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

            fun declaredFrom(argument: PsiElement?, isPlatform: Boolean): KtsDeclared? = when (argument) {
                // String notation: implementation("g:a:v")
                is KtStringTemplateExpression -> {
                    val notation = plainString(argument)
                    val parsed = notation?.let(GradleNotationParser::parse)
                    parsed?.let {
                        KtsDeclared(it.group, it.name, it.version, argument, isPlatform) { newVersion ->
                            BumpKtsVersionQuickFix(newVersion, BumpKtsVersionQuickFix.Mode.NOTATION)
                        }
                    }
                }

                // Catalog reference: implementation(libs.gson)
                is KtDotQualifiedExpression -> {
                    val text = argument.text
                    val resolved = if (text.startsWith("libs.")) catalog.resolve(text.removePrefix("libs.")) else null
                    resolved?.let {
                        KtsDeclared(it.group, it.name, it.version, argument, isPlatform) { newVersion ->
                            val versionKey = it.versionKey
                            if (versionKey != null && catalogFile != null) {
                                UpdateCatalogVersionQuickFix(
                                    versionKey, newVersion, catalog.referenceCount(versionKey), catalogFile.path,
                                )
                            } else {
                                null // inline catalog version: report-only in v1
                            }
                        }
                    }
                }

                else -> null // project(...), files(...): not external literals
            }

            when (val argument = arguments.first().getArgumentExpression()) {
                // Wrapper calls: platform("g:a:v"), enforcedPlatform(libs.bom),
                // kotlin("reflect", "1.9.24") — unwrap to the real declaration.
                is KtCallExpression -> {
                    val callee = (argument.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
                    when (callee) {
                        "platform", "enforcedPlatform" -> {
                            declaredFrom(argument.valueArguments.firstOrNull()?.getArgumentExpression(), isPlatform = true)
                                ?.let(result::add)
                        }
                        "kotlin" -> kotlinNotation(argument)?.let(result::add)
                        else -> Unit
                    }
                }

                else -> declaredFrom(argument, isPlatform = false)?.let(result::add)
            }
        }
        return result
    }

    /**
     * kotlin("reflect", "1.9.24") → org.jetbrains.kotlin:kotlin-reflect:1.9.24.
     * The far more common versionless form takes its version from the Kotlin
     * plugin, so there is nothing declared to check — skipped on purpose.
     */
    private fun kotlinNotation(call: KtCallExpression): KtsDeclared? {
        val arguments = call.valueArguments
        val module = plainString(arguments.firstOrNull()?.getArgumentExpression()) ?: return null
        if (":" in module) return null
        val versionArgument = arguments.drop(1).firstOrNull { arg ->
            val name = arg.getArgumentName()?.asName?.asString()
            name == null || name == "version"
        } ?: return null
        val versionExpr = versionArgument.getArgumentExpression() as? KtStringTemplateExpression ?: return null
        val version = plainString(versionExpr) ?: return null
        return KtsDeclared("org.jetbrains.kotlin", "kotlin-$module", version, versionExpr) { newVersion ->
            BumpKtsVersionQuickFix(newVersion, BumpKtsVersionQuickFix.Mode.WHOLE_LITERAL)
        }
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
    fun findCatalogFile(buildFile: VirtualFile?): VirtualFile? {
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
