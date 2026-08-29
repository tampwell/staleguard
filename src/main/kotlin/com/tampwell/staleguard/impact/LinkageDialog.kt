package com.tampwell.staleguard.impact

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.tampwell.staleguard.StaleguardBundle
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * The classpath audit result. Same shape as [ImpactDialog]: the headline
 * answers in one line, the tree exists only when there is something to act on,
 * and findings are grouped by the jar whose calls would fail, because that is
 * the jar whose version has to move.
 */
class LinkageDialog(
    project: Project,
    private val report: LinkageAudit.Report,
    private val ownCode: OwnCodeAudit.Standing = OwnCodeAudit.Standing.NothingBuilt,
) : DialogWrapper(project) {

    init {
        title = StaleguardBundle.message("linkage.dialog.title")
        init()
    }

    override fun createActions() = arrayOf(copyAction(), okAction)

    private fun copyAction(): javax.swing.Action = object : DialogWrapperAction(
        StaleguardBundle.message("impact.copy"),
    ) {
        override fun doAction(e: java.awt.event.ActionEvent) {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                java.awt.datatransfer.StringSelection(LinkageMarkdown.render(report, ownCode)),
                null,
            )
            close(OK_EXIT_CODE)
        }
    }

    /**
     * States what the own-code half of the verdict is allowed to claim. A
     * clean report with a partial build must not read as "your code is clean",
     * because the unbuilt modules were never checked.
     */
    private fun ownCodeLine(): JBLabel? = when (val standing = ownCode) {
        is OwnCodeAudit.Standing.Built ->
            if (report.clean) {
                JBLabel(
                    StaleguardBundle.message(
                        "linkage.owncode.clean",
                        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                            .format(java.util.Date(standing.asOfMillis)),
                    ),
                    AllIcons.General.InspectionsOK,
                    JBLabel.LEADING,
                )
            } else {
                null // the findings tree already speaks for itself
            }
        is OwnCodeAudit.Standing.PartiallyBuilt -> JBLabel(
            StaleguardBundle.message("linkage.owncode.partial", standing.missingModules.joinToString(", ")),
            AllIcons.General.Information,
            JBLabel.LEADING,
        )
        OwnCodeAudit.Standing.NothingBuilt -> JBLabel(
            StaleguardBundle.message("linkage.owncode.unbuilt"),
            AllIcons.General.Information,
            JBLabel.LEADING,
        )
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
        val headline = if (report.clean) {
            JBLabel(
                StaleguardBundle.message("linkage.verdict.clean", report.jarCount, report.refCount),
                AllIcons.General.InspectionsOK,
                JBLabel.LEADING,
            )
        } else {
            JBLabel(
                StaleguardBundle.message(
                    "linkage.verdict.broken",
                    report.brokenMembers.size,
                    report.evictedClasses.size,
                ),
                AllIcons.General.Warning,
                JBLabel.LEADING,
            )
        }
        val header = javax.swing.Box.createVerticalBox()
        header.add(headline)
        ownCodeLine()?.let { header.add(it) }
        panel.add(header, BorderLayout.NORTH)
        if (report.clean) return panel

        val root = DefaultMutableTreeNode(StaleguardBundle.message("linkage.tree.root"))
        for ((fromJar, broken) in report.brokenMembers.groupBy { it.fromJar }) {
            val jarNode = DefaultMutableTreeNode(
                StaleguardBundle.message("linkage.tree.jar", fromJar, broken.size),
            )
            for (entry in broken.groupBy { it.ref }.entries.sortedByDescending { it.value.size }) {
                jarNode.add(
                    DefaultMutableTreeNode(
                        StaleguardBundle.message(
                            "linkage.tree.member",
                            entry.key.display(),
                            entry.value.first().ownerJar ?: "?",
                        ),
                    ),
                )
            }
            root.add(jarNode)
        }
        for (evicted in report.evictedClasses.sortedByDescending { it.refCount }) {
            root.add(
                DefaultMutableTreeNode(
                    StaleguardBundle.message(
                        "linkage.tree.evicted",
                        evicted.owner.replace('/', '.'),
                        evicted.refCount,
                        evicted.fromJar,
                    ),
                ),
            )
        }
        val tree = Tree(DefaultTreeModel(root))
        tree.isRootVisible = true
        var row = 0
        while (row < tree.rowCount) {
            tree.expandRow(row)
            row++
        }
        val scroll = JBScrollPane(tree)
        scroll.preferredSize = java.awt.Dimension(JBUI.scale(700), JBUI.scale(380))
        panel.add(scroll, BorderLayout.CENTER)
        return panel
    }
}
