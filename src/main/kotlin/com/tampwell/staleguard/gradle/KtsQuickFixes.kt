package com.tampwell.staleguard.gradle

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.vfs.VirtualFile
import com.tampwell.staleguard.StaleguardBundle
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Version bump for Kotlin DSL string literals: `implementation("g:a:1.0")`
 * (swap inside the notation) or `version = "1.0"` named argument (replace
 * whole literal). Anchored on the KtStringTemplateExpression.
 */
class BumpKtsVersionQuickFix(
    private val newVersion: String,
    private val mode: Mode,
) : LocalQuickFix {

    enum class Mode { NOTATION, WHOLE_LITERAL }

    override fun getFamilyName(): String = StaleguardBundle.message("fix.bump.family")

    override fun getName(): String = StaleguardBundle.message("fix.bump.name", newVersion)

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val literal = descriptor.psiElement as? KtStringTemplateExpression ?: return
        val value = literal.entries.joinToString("") { it.text }
        val newContent = when (mode) {
            Mode.NOTATION -> {
                val parsed = GradleNotationParser.parse(value) ?: return
                GradleNotationParser.withVersion(value, parsed, newVersion)
            }
            Mode.WHOLE_LITERAL -> newVersion
        }
        literal.replace(KtPsiFactory(project).createExpression("\"$newContent\""))
    }
}

/**
 * Centralized catalog bump: edits the `[versions]` entry in
 * gradle/libs.versions.toml. Blast-radius confirmation when the version key
 * feeds more than one library (same safety rule as Maven properties). The
 * text edit uses the pure [VersionCatalog.versionValueRange] so table
 * boundaries are respected (a same-named key under [plugins] is never
 * touched).
 */
class UpdateCatalogVersionQuickFix(
    private val versionKey: String,
    private val newVersion: String,
    private val referenceCount: Int,
    private val catalogFilePath: String,
) : LocalQuickFix {

    override fun startInWriteAction(): Boolean = false // may show a dialog

    override fun getFamilyName(): String = StaleguardBundle.message("fix.catalog.family")

    override fun getName(): String = StaleguardBundle.message("fix.catalog.name", versionKey, newVersion)

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val catalogFile: VirtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath(catalogFilePath) ?: return

        if (referenceCount > 1) {
            val confirmed = MessageDialogBuilder.yesNo(
                StaleguardBundle.message("fix.catalog.impact.title"),
                StaleguardBundle.message("fix.catalog.impact.message", versionKey, referenceCount),
            ).ask(project)
            if (!confirmed) return
        }

        val document = FileDocumentManager.getInstance().getDocument(catalogFile) ?: return
        val range = VersionCatalog.versionValueRange(document.text, versionKey) ?: return
        WriteCommandAction.runWriteCommandAction(project, name, null, {
            document.replaceString(range.first, range.last + 1, newVersion)
        })
    }
}
