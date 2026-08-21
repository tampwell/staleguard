package com.tampwell.staleguard.gradle

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.tampwell.staleguard.StaleguardBundle

/**
 * Version bump for `implementation("g:a:${'$'}{libVersion}")` declarations: the
 * edit happens in gradle.properties, not the build file. Same shape as the
 * catalog fix — cross-file edit, blast-radius confirmation when the property
 * feeds several declarations, no preview (a preview of THIS file would lie).
 */
class UpdateGradlePropertyQuickFix(
    private val propertyKey: String,
    private val newVersion: String,
    private val propertiesFilePath: String,
) : LocalQuickFix, com.intellij.openapi.util.Iconable, com.intellij.codeInsight.intention.HighPriorityAction {

    override fun getIcon(flags: Int): javax.swing.Icon = com.intellij.icons.AllIcons.Actions.Edit

    override fun startInWriteAction(): Boolean = false // may show a dialog

    override fun generatePreview(
        project: Project,
        previewDescriptor: ProblemDescriptor,
    ): com.intellij.codeInsight.intention.preview.IntentionPreviewInfo =
        com.intellij.codeInsight.intention.preview.IntentionPreviewInfo.EMPTY

    override fun getFamilyName(): String = StaleguardBundle.message("fix.gradle.property.family")

    override fun getName(): String = StaleguardBundle.message("fix.gradle.property.name", propertyKey, newVersion)

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val propertiesFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath(propertiesFilePath) ?: return

        val usages = usageCount(project)
        if (usages > 1 &&
            propertyKey !in com.tampwell.staleguard.settings.StaleguardSettings.getInstance().state.suppressedPropertyWarnings
        ) {
            val confirmed = MessageDialogBuilder.yesNo(
                StaleguardBundle.message("fix.property.impact.title"),
                StaleguardBundle.message("fix.gradle.property.impact.message", propertyKey, usages),
            ).ask(project)
            if (!confirmed) return
        }

        val document = FileDocumentManager.getInstance().getDocument(propertiesFile) ?: return
        val range = GradleProperties.valueRange(document.text, propertyKey) ?: return
        WriteCommandAction.runWriteCommandAction(project, name, null, {
            document.replaceString(range.first, range.last + 1, newVersion)
        })
    }

    /** `${'$'}{key}` / `${'$'}key` occurrences across the project's Gradle build files. */
    private fun usageCount(project: Project): Int = try {
        // Application.runReadAction(Computable) — the one read-action entry
        // point the 26x line does NOT deprecate (ReadAction.compute and the
        // ActionsKt.runReadAction wrapper both are).
        com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction(com.intellij.openapi.util.Computable {
            val scope = com.intellij.psi.search.GlobalSearchScope.projectScope(project)
            val files = com.intellij.psi.search.FilenameIndex.getVirtualFilesByName("build.gradle", scope) +
                com.intellij.psi.search.FilenameIndex.getVirtualFilesByName("build.gradle.kts", scope)
            files.sumOf { file ->
                val text = String(file.contentsToByteArray())
                Regex("""\$\{?${Regex.escape(propertyKey)}\b}?""").findAll(text).count()
            }
        })
    } catch (_: Exception) {
        1
    }
}
