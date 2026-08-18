package com.tampwell.staleguard.inspection

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.settings.StaleguardSettings

/**
 * "Stop telling me about this library" — one click adds the coordinates to
 * the settings ignore list and re-highlights. The top mute-a-dependency
 * complaint against rival plugins, solved in the quick-fix menu itself.
 */
class IgnoreDependencyQuickFix(
    private val groupId: String,
    private val artifactId: String,
) : LocalQuickFix, com.intellij.openapi.util.Iconable, com.intellij.codeInsight.intention.LowPriorityAction {

    override fun startInWriteAction(): Boolean = false

    override fun getIcon(flags: Int): javax.swing.Icon = com.intellij.icons.AllIcons.Actions.Cancel

    override fun getFamilyName(): String = StaleguardBundle.message("fix.ignore.family")

    override fun getName(): String = StaleguardBundle.message("fix.ignore.name", "$groupId:$artifactId")

    // Mutates settings — must never run during intention preview.
    override fun generatePreview(
        project: Project,
        previewDescriptor: ProblemDescriptor,
    ): com.intellij.codeInsight.intention.preview.IntentionPreviewInfo =
        com.intellij.codeInsight.intention.preview.IntentionPreviewInfo.EMPTY

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val settings = StaleguardSettings.getInstance()
        val entry = "$groupId:$artifactId"
        if (entry !in settings.state.ignoredCoordinates) {
            settings.state.ignoredCoordinates.add(entry)
        }
        descriptor.psiElement?.containingFile?.let {
            DaemonCodeAnalyzer.getInstance(project).restart(it)
        }
    }
}
