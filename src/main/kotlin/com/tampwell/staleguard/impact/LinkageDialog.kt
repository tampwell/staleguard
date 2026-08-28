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
class LinkageDialog(project: Project, private val report: LinkageAudit.Report) : DialogWrapper(project) {

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
                java.awt.datatransfer.StringSelection(LinkageMarkdown.render(report)),
                null,
            )
            close(OK_EXIT_CODE)
        }
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
        panel.add(headline, BorderLayout.NORTH)
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
