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

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        BrowserUtil.browse(changelogUrl)
    }
}
