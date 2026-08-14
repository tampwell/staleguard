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
    private val fixFactory: (String) -> LocalQuickFix?,
) {
    fun fix(newVersion: String): LocalQuickFix? = fixFactory(newVersion)
}

internal object KtsDependencyCollector {

    fun collect(
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
