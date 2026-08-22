package com.tampwell.staleguard.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.repository.DeclaredRepositories
import com.tampwell.staleguard.repository.RepositoryCredentials
import javax.swing.DefaultListModel
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Manages per-host repository credentials. The dialog shows only hosts and
 * usernames — passwords go straight from the field into the PasswordSafe on a
 * pooled thread and are never displayed back.
 */
class RepositoryCredentialsDialog(private val project: Project?) : DialogWrapper(project) {

    private val model = DefaultListModel<String>()
    private val list = JBList(model)

    init {
        title = StaleguardBundle.message("credentials.dialog.title")
        RepositoryCredentials.getInstance().configuredHosts().sorted().forEach(model::addElement)
        init()
    }

    override fun createActions() = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        val decorated = ToolbarDecorator.createDecorator(list)
            .setAddAction { addEntry() }
            .setRemoveAction { removeSelected() }
            .disableUpDownActions()
            .createPanel()
        decorated.preferredSize = JBUI.size(420, 180)
        return panel {
            row { cell(decorated).align(AlignX.FILL) }
            row {
                button(StaleguardBundle.message("credentials.import.button")) { importFromSettingsXml() }
            }
            row { comment(StaleguardBundle.message("credentials.dialog.comment")) }
        }
    }

    /**
     * Explicit import from ~/.m2/settings.xml — the user sees exactly which
     * servers were found and ticks the ones to bring over. Encrypted
     * passwords are shown but never decrypted (that would require Maven's
     * master key, which this plugin must never touch).
     */
    private fun importFromSettingsXml() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val settingsXml = SettingsXmlImport.readSettingsFile()
            if (settingsXml == null) {
                SwingUtilities.invokeLater {
                    Messages.showInfoMessage(
                        StaleguardBundle.message("credentials.import.none.found"),
                        StaleguardBundle.message("credentials.dialog.title"),
                    )
                }
                return@executeOnPooledThread
            }
            val candidates = SettingsXmlImport.candidates(settingsXml, openProjectPomRepoIds())
            SwingUtilities.invokeLater {
                if (candidates.isEmpty()) {
                    Messages.showInfoMessage(
                        StaleguardBundle.message("credentials.import.no.servers"),
                        StaleguardBundle.message("credentials.dialog.title"),
                    )
                    return@invokeLater
                }
                val picker = ImportPickerDialog(project, candidates)
                if (!picker.showAndGet()) return@invokeLater
                val chosen = picker.selected()
                ApplicationManager.getApplication().executeOnPooledThread {
                    for (candidate in chosen) {
                        RepositoryCredentials.getInstance()
                            .set(candidate.host!!, candidate.username!!, candidate.password!!.toCharArray())
                    }
                    SwingUtilities.invokeLater {
                        chosen.mapNotNull { it.host }.forEach { host ->
                            if (!model.contains(host)) model.addElement(host)
                        }
                    }
                }
            }
        }
    }

    /** (id, url) pairs from every pom.xml in currently open projects; empty when unavailable. */
    private fun openProjectPomRepoIds(): List<Pair<String, String>> = runCatching {
        ProjectManager.getInstance().openProjects.flatMap { openProject ->
            // Application.runReadAction(Computable) — the read-action entry
            // point that stays non-deprecated across the whole 243-262 range.
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .runReadAction(com.intellij.openapi.util.Computable<List<Pair<String, String>>> {
                FilenameIndex
                    .getVirtualFilesByName("pom.xml", GlobalSearchScope.projectScope(openProject))
                    .flatMap { file ->
                        runCatching {
                            DeclaredRepositories.pomRepositoriesWithIds(String(file.contentsToByteArray()))
                        }.getOrDefault(emptyList())
                    }
            })
        }
    }.getOrDefault(emptyList())

    /** Checkbox per server: importable ones enabled and preselected, the rest labeled with why not. */
    private class ImportPickerDialog(
        project: Project?,
        private val candidates: List<SettingsXmlImport.Candidate>,
    ) : DialogWrapper(project) {

        private val boxes = mutableMapOf<JCheckBox, SettingsXmlImport.Candidate>()

        init {
            title = StaleguardBundle.message("credentials.import.title")
            init()
        }

        fun selected(): List<SettingsXmlImport.Candidate> =
            boxes.filterKeys { it.isSelected && it.isEnabled }.values.toList()

        override fun createCenterPanel(): JComponent = panel {
            for (candidate in candidates.sortedBy { it.serverId }) {
                row {
                    val label = when {
                        candidate.encrypted -> StaleguardBundle.message(
                            "credentials.import.row.encrypted", candidate.serverId,
                        )
                        candidate.host == null -> StaleguardBundle.message(
                            "credentials.import.row.nohost", candidate.serverId,
                        )
                        !candidate.importable -> StaleguardBundle.message(
                            "credentials.import.row.incomplete", candidate.serverId,
                        )
                        else -> StaleguardBundle.message(
                            "credentials.import.row", candidate.serverId, candidate.host, candidate.username ?: "",
                        )
                    }
                    val box = JCheckBox(label, candidate.importable)
                    box.isEnabled = candidate.importable
                    boxes[box] = candidate
                    cell(box)
                }
            }
            row { comment(StaleguardBundle.message("credentials.import.comment")) }
        }
    }

    private fun addEntry() {
        val entry = EntryDialog(project)
        if (!entry.showAndGet()) return
        val host = RepositoryCredentials.hostOf("https://" + entry.host().removePrefix("https://").removePrefix("http://"))
            ?: entry.host().trim().lowercase()
        val username = entry.username()
        val password = entry.password()
        ApplicationManager.getApplication().executeOnPooledThread {
            RepositoryCredentials.getInstance().set(host, username, password)
            password.fill('\u0000')
            SwingUtilities.invokeLater {
                if (!model.contains(host)) model.addElement(host)
            }
        }
    }

    private fun removeSelected() {
        val host = list.selectedValue ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            RepositoryCredentials.getInstance().remove(host)
        }
        model.removeElement(host)
    }

    /** Host + username + password entry form. Password field is write-only. */
    private class EntryDialog(project: Project?) : DialogWrapper(project) {
        private val hostField = JBTextField(28)
        private val userField = JBTextField(28)
        private val passwordField = JBPasswordField()

        init {
            title = StaleguardBundle.message("credentials.entry.title")
            init()
        }

        fun host(): String = hostField.text.trim()
        fun username(): String = userField.text.trim()
        fun password(): CharArray = passwordField.password

        override fun createCenterPanel(): JComponent = panel {
            row(StaleguardBundle.message("credentials.entry.host")) { cell(hostField).align(AlignX.FILL) }
            row(StaleguardBundle.message("credentials.entry.username")) { cell(userField).align(AlignX.FILL) }
            row(StaleguardBundle.message("credentials.entry.password")) { cell(passwordField).align(AlignX.FILL) }
            row { comment(StaleguardBundle.message("credentials.entry.comment")) }
        }

        override fun doValidate() = when {
            host().isEmpty() -> com.intellij.openapi.ui.ValidationInfo(
                StaleguardBundle.message("credentials.validation.host"), hostField,
            )
            username().isEmpty() -> com.intellij.openapi.ui.ValidationInfo(
                StaleguardBundle.message("credentials.validation.username"), userField,
            )
            else -> null
        }

        override fun getPreferredFocusedComponent(): JComponent = hostField
    }
}
