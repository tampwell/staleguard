package com.tampwell.staleguard.gradle

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.tampwell.staleguard.StaleguardBundle

/**
 * Version bump for `implementation("g:a:${'$'}{Versions.gson}")` declarations:
 * the edit happens on the buildSrc constant. Same shape as the property fix -
 * cross-file edit, blast-radius confirmation when the constant feeds several
 * declarations, no preview (a preview of THIS file would lie). The Versions
 * source file is located at APPLY time from the build file, so the
 * highlighting pass pays nothing for the possibility.
 */
class UpdateBuildSrcVersionQuickFix(
    private val key: String,
    private val newVersion: String,
    private val buildFilePath: String,
) : LocalQuickFix, com.intellij.openapi.util.Iconable, com.intellij.codeInsight.intention.HighPriorityAction {

    override fun getIcon(flags: Int): javax.swing.Icon = com.intellij.icons.AllIcons.Actions.Edit

    override fun startInWriteAction(): Boolean = false // may show a dialog

    override fun generatePreview(
        project: Project,
        previewDescriptor: ProblemDescriptor,
    ): com.intellij.codeInsight.intention.preview.IntentionPreviewInfo =
        com.intellij.codeInsight.intention.preview.IntentionPreviewInfo.EMPTY

    override fun getFamilyName(): String = StaleguardBundle.message("fix.buildsrc.family")

    override fun getName(): String = StaleguardBundle.message("fix.buildsrc.name", key, newVersion)

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val buildFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath(buildFilePath) ?: return
        val versionsFile = BuildSrcVersions.fileFor(buildFile, key) ?: return

        val usages = usageCount(project)
        if (usages > 1 &&
            key !in com.tampwell.staleguard.settings.StaleguardSettings.getInstance().state.suppressedPropertyWarnings
        ) {
            val confirmed = MessageDialogBuilder.yesNo(
                StaleguardBundle.message("fix.property.impact.title"),
                StaleguardBundle.message("fix.buildsrc.impact.message", key, usages),
            ).ask(project)
            if (!confirmed) return
        }

        val document = FileDocumentManager.getInstance().getDocument(versionsFile) ?: return
        val range = BuildSrcVersions.valueRange(document.text, key) ?: return
        WriteCommandAction.runWriteCommandAction(project, name, null, {
            document.replaceString(range.first, range.last + 1, newVersion)
        })
    }

    /** `${'$'}{Versions.x}` / `${'$'}Versions.x` occurrences across the project's Gradle build files. */
    private fun usageCount(project: Project): Int = try {
        com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction(com.intellij.openapi.util.Computable {
            val scope = com.intellij.psi.search.GlobalSearchScope.projectScope(project)
            val files = com.intellij.psi.search.FilenameIndex.getVirtualFilesByName("build.gradle", scope) +
                com.intellij.psi.search.FilenameIndex.getVirtualFilesByName("build.gradle.kts", scope)
            files.sumOf { file ->
                val text = String(file.contentsToByteArray())
                Regex("""\$\{?${Regex.escape(key)}\b}?""").findAll(text).count()
            }
        })
    } catch (_: Exception) {
        1
    }
}
