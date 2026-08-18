package com.tampwell.staleguard.toml

import com.intellij.codeInsight.intention.HighPriorityAction
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.util.Iconable
import com.intellij.psi.PsiDocumentManager
import com.tampwell.staleguard.StaleguardBundle
import javax.swing.Icon

/**
 * Version bump inside the catalog file itself: replaces the quoted literal
 * the problem is anchored on. When the entry is a shared `[versions]` key the
 * same blast-radius confirmation applies as everywhere else — one edit here
 * moves every library and plugin referencing the key.
 */
class BumpTomlVersionQuickFix(
    private val newVersion: String,
    private val referenceCount: Int,
    private val versionKey: String?,
) : LocalQuickFix, Iconable, HighPriorityAction {

    override fun getIcon(flags: Int): Icon = AllIcons.Actions.Edit

    override fun startInWriteAction(): Boolean = false // may show a dialog

    // The blast-radius confirmation must never fire while the user merely
    // browses the Alt+Enter menu.
    override fun generatePreview(
        project: Project,
        previewDescriptor: ProblemDescriptor,
    ): com.intellij.codeInsight.intention.preview.IntentionPreviewInfo =
        com.intellij.codeInsight.intention.preview.IntentionPreviewInfo.EMPTY

    override fun getFamilyName(): String = StaleguardBundle.message("fix.bump.family")

    override fun getName(): String = StaleguardBundle.message("fix.bump.name", newVersion)

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val literal = descriptor.psiElement ?: return
        if (referenceCount > 1 && versionKey != null) {
            val confirmed = MessageDialogBuilder.yesNo(
                StaleguardBundle.message("fix.catalog.impact.title"),
                StaleguardBundle.message("fix.catalog.impact.message", versionKey, referenceCount),
            ).ask(project)
            if (!confirmed) return
        }
        val file = literal.containingFile
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        val range = literal.textRange
        WriteCommandAction.runWriteCommandAction(project, name, null, {
            document.replaceString(range.startOffset, range.endOffset, "\"$newVersion\"")
        }, file)
    }
}
