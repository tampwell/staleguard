package com.tampwell.staleguard.impact

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.tampwell.staleguard.StaleguardBundle
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * The impact report. The headline answers the question in one line; the tree
 * below it exists only when there is something to act on, and every leaf
 * navigates to the call site that has to change.
 */
class ImpactDialog(private val project: Project, private val report: ImpactReport) : DialogWrapper(project) {

    init {
        title = StaleguardBundle.message(
            "impact.dialog.title",
            report.coordinate,
            report.fromVersion,
            report.toVersion,
        )
        init()
    }

    override fun createActions() = arrayOf(copyAction(), okAction)

    private fun copyAction(): javax.swing.Action = object : DialogWrapperAction(
        StaleguardBundle.message("impact.copy"),
    ) {
        override fun doAction(e: java.awt.event.ActionEvent) {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                java.awt.datatransfer.StringSelection(ImpactMarkdown.render(report)),
                null,
            )
            close(OK_EXIT_CODE)
        }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
        val header = Box.createVerticalBox()
        header.add(headline())
        if (report.searchTruncated) {
            header.add(
                JBLabel(
                    StaleguardBundle.message("impact.truncated", RemovedMemberUsageSearch.MAX_MEMBERS_SEARCHED),
                    AllIcons.General.Information,
                    JBLabel.LEADING,
                ),
            )
        }
        panel.add(header, BorderLayout.NORTH)

        if (report.usages.isEmpty()) return panel
        val scroll = JBScrollPane(usageTree())
        // A big upgrade can list hundreds of call sites; without a bound the
        // tree's preferred size dictates the dialog's and it fills the screen.
        scroll.preferredSize = java.awt.Dimension(JBUI.scale(640), JBUI.scale(360))
        panel.add(scroll, BorderLayout.CENTER)
        return panel
    }

    private fun headline(): JBLabel {
        val (text, icon) = when (report.verdict) {
            ImpactVerdict.BREAKS -> StaleguardBundle.message(
                "impact.verdict.breaks",
                report.usages.size,
                report.affectedCallSites,
                report.removedTotal,
            ) to AllIcons.General.Warning

            ImpactVerdict.REMOVALS_UNUSED -> StaleguardBundle.message(
                "impact.verdict.unused",
                report.removedTotal,
            ) to AllIcons.General.InspectionsOK

            ImpactVerdict.NO_REMOVALS ->
                StaleguardBundle.message("impact.verdict.noremovals") to AllIcons.General.InspectionsOK

            ImpactVerdict.UNKNOWN -> incompleteMessage() to AllIcons.General.Warning
        }
        return JBLabel(text, icon, JBLabel.LEADING)
    }

    private fun incompleteMessage(): String = StaleguardBundle.message(
        when (report.incomplete) {
            ImpactReport.Incomplete.OFFLINE -> "impact.incomplete.offline"
            ImpactReport.Incomplete.CURRENT_JAR_UNAVAILABLE -> "impact.incomplete.current"
            ImpactReport.Incomplete.CANDIDATE_JAR_UNAVAILABLE -> "impact.incomplete.candidate"
            null -> "impact.incomplete.candidate"
        },
        report.coordinate,
        report.fromVersion,
        report.toVersion,
    )

    private fun usageTree(): Tree {
        val root = DefaultMutableTreeNode(
            StaleguardBundle.message("impact.tree.root", report.usages.size, report.affectedCallSites),
        )
        for (usage in report.usages) {
            val memberNode = DefaultMutableTreeNode(usage.member.display())
            for (location in usage.locations) {
                memberNode.add(
                    DefaultMutableTreeNode(
                        Navigable(
                            StaleguardBundle.message(
                                "impact.tree.location",
                                location.presentablePath,
                                location.line,
                            ),
                            location,
                        ),
                    ),
                )
            }
            root.add(memberNode)
        }

        val tree = Tree(DefaultTreeModel(root))
        tree.isRootVisible = true
        // rowCount grows as rows expand, so this has to re-read it each pass.
        var row = 0
        while (row < tree.rowCount) {
            tree.expandRow(row)
            row++
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                val target = node.userObject as? Navigable ?: return
                val file = VirtualFileManager.getInstance().findFileByUrl(target.location.fileUrl) ?: return
                OpenFileDescriptor(project, file, target.location.offset).navigate(true)
                close(OK_EXIT_CODE)
            }
        })
        return tree
    }

    private class Navigable(private val label: String, val location: UsageLocation) {
        override fun toString(): String = label
    }
}
