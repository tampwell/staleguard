package com.tampwell.staleguard.inspection

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.tampwell.staleguard.StaleguardBundle

/**
 * Opens the OSV advisory page for a flagged vulnerability. The osv.dev page
 * aggregates the CVE text, affected ranges, and upstream references, which is
 * exactly the triage a developer does before deciding how fast to move.
 */
class OpenAdvisoryQuickFix(
    private val advisoryUrl: String,
    private val displayId: String,
) : LocalQuickFix, com.intellij.openapi.util.Iconable {

    override fun startInWriteAction(): Boolean = false

    override fun getIcon(flags: Int): javax.swing.Icon = com.intellij.icons.AllIcons.General.Warning

    override fun getFamilyName(): String = StaleguardBundle.message("fix.advisory.family")

    override fun getName(): String = StaleguardBundle.message("fix.advisory.name", displayId)

    // Opens a browser — must never run during intention preview.
    override fun generatePreview(
        project: Project,
        previewDescriptor: ProblemDescriptor,
    ): com.intellij.codeInsight.intention.preview.IntentionPreviewInfo =
        com.intellij.codeInsight.intention.preview.IntentionPreviewInfo.EMPTY

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        BrowserUtil.browse(advisoryUrl)
    }
}
