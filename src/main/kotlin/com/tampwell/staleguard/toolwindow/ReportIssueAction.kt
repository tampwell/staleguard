package com.tampwell.staleguard.toolwindow

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationNamesInfo
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.StaleguardVersion
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Opens the GitHub bug form with the boring fields already filled in.
 *
 * Nobody reports a wrong suggestion if reporting means finding the repo,
 * remembering their IDE build, and describing their setup from scratch.
 * The two fields the maintainer always has to ask for are the two this
 * fills automatically, which leaves the reporter only the part they
 * actually know: what looked wrong.
 */
internal class ReportIssueAction : AnAction(
    StaleguardBundle.message("toolwindow.report"),
    null,
    com.intellij.icons.AllIcons.Actions.Help,
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        BrowserUtil.browse(issueUrl(ideDescription(), pluginVersion()))
    }

    private fun ideDescription(): String {
        val info = ApplicationInfo.getInstance()
        val name = ApplicationNamesInfo.getInstance().fullProductName
        return "$name ${info.fullVersion} (build ${info.build.asString()})"
    }

    private fun pluginVersion(): String = StaleguardVersion.current()

    companion object {
        private const val FORM = "https://github.com/tampwell/staleguard/issues/new"

        /** GitHub issue forms prefill by field id; ours are `ide` and `plugin-version`. */
        fun issueUrl(ide: String, pluginVersion: String): String {
            fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
            return "$FORM?template=bug_report.yml" +
                "&ide=${encode(ide)}" +
                "&plugin-version=${encode(pluginVersion)}"
        }
    }
}
