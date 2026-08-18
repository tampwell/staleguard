package com.tampwell.staleguard.inspection

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.tampwell.staleguard.StaleguardBundle

/**
 * "What changed?" is the question that blocks updates — one Alt+Enter opens
 * the project's releases page (derived from the newest .pom's <scm> tag,
 * cached; no extra network). User-initiated browser open only.
 */
class OpenChangelogQuickFix(private val changelogUrl: String) : LocalQuickFix, com.intellij.openapi.util.Iconable {

    override fun startInWriteAction(): Boolean = false

    override fun getIcon(flags: Int): javax.swing.Icon = com.intellij.icons.AllIcons.Vcs.History

    override fun getFamilyName(): String = StaleguardBundle.message("fix.changelog.family")

    override fun getName(): String = StaleguardBundle.message("fix.changelog.name")

    // Opens a browser — must never run during intention preview.
    override fun generatePreview(
        project: Project,
        previewDescriptor: ProblemDescriptor,
    ): com.intellij.codeInsight.intention.preview.IntentionPreviewInfo =
        com.intellij.codeInsight.intention.preview.IntentionPreviewInfo.EMPTY

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        BrowserUtil.browse(changelogUrl)
    }
}
