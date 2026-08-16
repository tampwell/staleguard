package com.tampwell.staleguard.scaffold

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VfsUtil
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.services.VersionLookupService
import com.tampwell.staleguard.version.VersionSuggestion
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Tools → "Staleguard: New Library Try-Out Script…" — from coordinates to a
 * runnable scratch script in the chosen language, with the version pre-filled
 * from Staleguard's own warm cache when the user leaves it blank.
 */
class TryOutScriptAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dialog = TryOutDialog(project)
        if (!dialog.showAndGet()) return

        val lang = dialog.lang()
        val version = dialog.version().ifBlank {
            latestKnown(dialog.groupId(), dialog.artifactId()) ?: return
        }
        val fileName = TryOutScripts.fileName(dialog.artifactId(), lang)
        val content = TryOutScripts.render(lang, dialog.groupId(), dialog.artifactId(), version)

        val base = project.basePath ?: return
        WriteAction.run<RuntimeException> {
            val dir = VfsUtil.createDirectoryIfMissing("$base/staleguard-tryout") ?: return@run
            val file = dir.findChild(fileName) ?: dir.createChildData(this, fileName)
            VfsUtil.saveText(file, content)
            OpenFileDescriptor(project, file).navigate(true)
        }
    }

    private fun latestKnown(groupId: String, artifactId: String): String? {
        val known = VersionLookupService.getInstance().peek(Coordinates(groupId, artifactId))?.value
            ?: return null
        return VersionSuggestion.suggest(null, known.versions, includePrereleases = false)?.value
    }

    private class TryOutDialog(project: Project) : DialogWrapper(project) {
        private val group = JTextField(24)
        private val artifact = JTextField(24)
        private val version = JTextField(24)
        private val langs = ComboBox(TryOutScripts.Lang.values().map { it.display }.toTypedArray())

        init {
            title = StaleguardBundle.message("tryout.dialog.title")
            version.toolTipText = StaleguardBundle.message("tryout.version.tooltip")
            init()
        }

        fun groupId(): String = group.text.trim()
        fun artifactId(): String = artifact.text.trim()
        fun version(): String = version.text.trim()
        fun lang(): TryOutScripts.Lang = TryOutScripts.Lang.values()[langs.selectedIndex]

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(GridBagLayout())
            val c = GridBagConstraints()
            c.insets = Insets(4, 4, 4, 4)
            c.anchor = GridBagConstraints.WEST
            var row = 0
            for ((label, field) in listOf(
                StaleguardBundle.message("tryout.group") to group,
                StaleguardBundle.message("tryout.artifact") to artifact,
                StaleguardBundle.message("tryout.version") to version,
                StaleguardBundle.message("tryout.language") to langs,
            )) {
                c.gridx = 0
                c.gridy = row
                panel.add(JLabel(label), c)
                c.gridx = 1
                panel.add(field, c)
                row++
            }
            return panel
        }

        override fun doValidate(): ValidationInfo? = when {
            groupId().isEmpty() -> ValidationInfo(StaleguardBundle.message("tryout.validation.group"), group)
            artifactId().isEmpty() -> ValidationInfo(StaleguardBundle.message("tryout.validation.artifact"), artifact)
            else -> null
        }

        override fun getPreferredFocusedComponent(): JComponent = group
    }
}
