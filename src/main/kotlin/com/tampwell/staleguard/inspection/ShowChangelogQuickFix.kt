package com.tampwell.staleguard.inspection

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.changelog.ChangelogEngine
import com.tampwell.staleguard.services.ChangelogService
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * "Show what changed" — fetches the release notes for every version between
 * current and suggested (one changelog fetch when the project keeps one) and
 * shows them in-IDE, with a banner when the notes themselves talk about
 * breaking changes. Network happens here, on explicit intent with a progress
 * bar — never during highlighting.
 */
class ShowChangelogQuickFix(
    private val coordinate: String,
    private val scmValue: String?,
    private val artifactId: String?,
    private val currentVersion: String,
    private val suggestedVersion: String,
    private val allVersions: List<String>,
) : LocalQuickFix, com.intellij.openapi.util.Iconable {

    override fun getIcon(flags: Int): javax.swing.Icon = com.intellij.icons.AllIcons.Actions.Preview

    override fun startInWriteAction(): Boolean = false

    override fun getFamilyName(): String = StaleguardBundle.message("fix.whatchanged.family")

    override fun getName(): String =
        StaleguardBundle.message("fix.whatchanged.name", currentVersion, suggestedVersion)

    // Fetches from the network and opens a dialog — never during preview.
    override fun generatePreview(
        project: Project,
        previewDescriptor: ProblemDescriptor,
    ): com.intellij.codeInsight.intention.preview.IntentionPreviewInfo =
        com.intellij.codeInsight.intention.preview.IntentionPreviewInfo.EMPTY

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        object : Task.Backgroundable(project, StaleguardBundle.message("whatchanged.progress", coordinate), true) {
            private var summary: ChangelogEngine.Summary? = null

            override fun run(indicator: ProgressIndicator) {
                summary = ChangelogService.getInstance()
                    .summarize(coordinate, scmValue, artifactId, currentVersion, suggestedVersion, allVersions)
            }

            override fun onSuccess() {
                NotesDialog(project, coordinate, currentVersion, suggestedVersion, summary).show()
            }
        }.queue()
    }

    private class NotesDialog(
        project: Project,
        coordinate: String,
        from: String,
        to: String,
        private val summary: ChangelogEngine.Summary?,
    ) : DialogWrapper(project) {

        init {
            title = StaleguardBundle.message("whatchanged.dialog.title", coordinate, from, to)
            init()
        }

        override fun createActions() = arrayOf(okAction)

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
            val found = summary
            if (found == null) {
                panel.add(JBLabel(StaleguardBundle.message("whatchanged.none")), BorderLayout.CENTER)
                return panel
            }

            if (found.signals.hasBreaking) {
                panel.add(
                    JBLabel(
                        StaleguardBundle.message("whatchanged.breaking.banner", found.signals.strong.first()),
                        com.intellij.icons.AllIcons.General.Warning,
                        JBLabel.LEADING,
                    ),
                    BorderLayout.NORTH,
                )
            }

            val text = buildString {
                for (notes in found.notes) {
                    appendLine("== ${notes.version} ==")
                    appendLine(notes.body)
                    appendLine()
                }
                if (found.uncovered.isNotEmpty()) {
                    appendLine(StaleguardBundle.message("whatchanged.uncovered", found.uncovered.joinToString(", ")))
                }
                append(StaleguardBundle.message("whatchanged.source", found.sourceUrl))
            }
            val area = JTextArea(text, 24, 80)
            area.isEditable = false
            area.lineWrap = true
            area.wrapStyleWord = true
            area.caretPosition = 0
            panel.add(JBScrollPane(area), BorderLayout.CENTER)
            return panel
        }
    }
}
