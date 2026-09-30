package com.tampwell.staleguard.checkup

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.impact.LinkageDialog
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * The checkup on one page. Every fix button hands off to a flow that already
 * exists and is already tested (the batch updater, the linkage doctor's
 * apply), so the checkup adds a view, never a second way to edit a build.
 */
class CheckupDialog(
    private val project: Project,
    private val outcome: CheckupService.Outcome,
    /** The score from the previous checkup of this project, when there was one. */
    private val previousScore: Int?,
) : DialogWrapper(project) {

    private val report get() = outcome.report

    init {
        title = StaleguardBundle.message("checkup.dialog.title")
        init()
    }

    override fun createActions() = listOfNotNull(
        updateAction(),
        linkageAction(),
        copyAction(),
        okAction,
    ).toTypedArray()

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(10)))
        panel.add(header(), BorderLayout.NORTH)

        val root = DefaultMutableTreeNode()
        if (report.findings.isEmpty()) {
            root.add(DefaultMutableTreeNode(StaleguardBundle.message("checkup.clean")))
        }
        for (tier in CheckupReport.Tier.entries) {
            val inTier = report.findings.filter { it.tier == tier }
            if (inTier.isEmpty()) continue
            val tierNode = DefaultMutableTreeNode("${CheckupReport.tierTitle(tier)} (${inTier.size})")
            for (finding in inTier) {
                val node = DefaultMutableTreeNode(Row(finding))
                finding.detail?.let { node.add(DefaultMutableTreeNode(it)) }
                tierNode.add(node)
            }
            root.add(tierNode)
        }
        val tree = Tree(DefaultTreeModel(root))
        tree.isRootVisible = false
        tree.showsRootHandles = true
        // Open the top of the list: that is what the reader should act on.
        for (i in 0 until minOf(2, root.childCount)) tree.expandRow(i)
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                val location = (node.userObject as? Row)?.finding?.target as? CheckupService.Location ?: return
                OpenFileDescriptor(project, location.file, location.offset).navigate(true)
            }
        })
        panel.add(JBScrollPane(tree), BorderLayout.CENTER)

        if (report.deductions.isNotEmpty()) {
            val breakdown = report.deductions.joinToString("<br>") { "-${it.points} &nbsp;${escape(it.reason)}" }
            panel.add(
                JBLabel("<html><small>${StaleguardBundle.message("checkup.breakdown")}<br>$breakdown</small></html>").apply {
                    foreground = JBColor.GRAY
                },
                BorderLayout.SOUTH,
            )
        }
        panel.preferredSize = Dimension(JBUI.scale(760), JBUI.scale(520))
        return panel
    }

    private fun header(): JComponent {
        val color = when (report.grade) {
            "A" -> "#3a9d4f"
            "B" -> "#6b9d3a"
            "C" -> "#c9a227"
            "D" -> "#d4762b"
            else -> "#cf4b3f"
        }
        val trend = previousScore?.let { before ->
            val delta = report.score - before
            when {
                delta > 0 -> StaleguardBundle.message("checkup.trend.up", delta, before)
                delta < 0 -> StaleguardBundle.message("checkup.trend.down", -delta, before)
                else -> StaleguardBundle.message("checkup.trend.same")
            }
        }.orEmpty()
        val counts = report.findings.groupingBy { it.tier }.eachCount()
        val summary = StaleguardBundle.message(
            "checkup.summary",
            report.inputs.dependencies,
            counts[CheckupReport.Tier.FIX_NOW] ?: 0,
            counts[CheckupReport.Tier.FIX_SOON] ?: 0,
        )
        return JBLabel(
            "<html><span style='font-size:28pt;color:$color'><b>${report.score}</b></span>" +
                "<span style='font-size:14pt;color:$color'>&nbsp;/ 100 &nbsp;${report.grade}</span>" +
                "&nbsp;&nbsp;<span>${escape(trend)}</span><br>${escape(summary)}</html>",
        )
    }

    /** Offered when anything is out of date or vulnerable: security fixes come preselected there. */
    private fun updateAction(): javax.swing.Action? {
        val inputs = report.inputs
        val any = inputs.vulnerabilities.any { it.declared } || inputs.majorUpdates + inputs.minorUpdates + inputs.patchUpdates > 0
        if (!any) return null
        return object : DialogWrapperAction(StaleguardBundle.message("checkup.update")) {
            override fun doAction(e: java.awt.event.ActionEvent) {
                close(OK_EXIT_CODE)
                val action = ActionManager.getInstance().getAction("com.tampwell.staleguard.BatchUpdate") ?: return
                ActionManager.getInstance().tryToExecute(action, null, null, "StaleguardCheckup", true)
            }
        }
    }

    private fun linkageAction(): javax.swing.Action? {
        val linkage = outcome.linkage.report
        if (linkage.clean && linkage.shadowedGroups.isEmpty()) return null
        return object : DialogWrapperAction(StaleguardBundle.message("checkup.linkage")) {
            override fun doAction(e: java.awt.event.ActionEvent) {
                close(OK_EXIT_CODE)
                LinkageDialog(project, outcome.linkage).show()
            }
        }
    }

    private fun copyAction(): javax.swing.Action = object : DialogWrapperAction(StaleguardBundle.message("checkup.copy")) {
        override fun doAction(e: java.awt.event.ActionEvent) {
            CopyPasteManager.getInstance().setContents(StringSelection(CheckupReport.markdown(report, project.name)))
        }
    }

    private class Row(val finding: CheckupReport.Finding) {
        override fun toString(): String = finding.title
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
