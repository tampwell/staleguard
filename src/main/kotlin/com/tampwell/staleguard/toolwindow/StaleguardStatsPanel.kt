package com.tampwell.staleguard.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.maven.PomDependencyCollector
import com.tampwell.staleguard.plan.PlannerInput
import com.tampwell.staleguard.plan.StatsCalculator
import com.tampwell.staleguard.plan.UpgradePlanner
import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.services.FreshnessListener
import com.tampwell.staleguard.services.FreshnessRefreshService
import com.tampwell.staleguard.services.VersionLookupService
import com.tampwell.staleguard.settings.StaleguardSettings
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * The Staleguard tool window: project summary, per-module freshness stats,
 * and navigable upgrade entries. Reads only the warm cache; opening the
 * window enqueues background lookups for anything unresolved, and the
 * message-bus [FreshnessListener] rebuilds the view as answers arrive.
 */
class StaleguardStatsPanel(private val project: Project) :
    SimpleToolWindowPanel(true, true), Disposable {

    private val tree = Tree()

    init {
        toolbar = buildToolbar()
        setContent(JBScrollPane(tree))
        tree.isRootVisible = true

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                val target = node.userObject as? NavTarget ?: return
                OpenFileDescriptor(project, target.file, target.offset).navigate(true)
            }
        })

        project.messageBus.connect(this)
            .subscribe(FreshnessListener.TOPIC, FreshnessListener { SwingUtilities.invokeLater { rebuild() } })

        rebuild()
    }

    override fun dispose() = Unit

    private fun buildToolbar(): JComponent {
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("StaleguardStats", DefaultActionGroup(RefreshAllAction()), true)
        toolbar.targetComponent = this
        return toolbar.component
    }

    private inner class RefreshAllAction : AnAction(
        StaleguardBundle.message("toolwindow.refresh"),
        null,
        AllIcons.Actions.Refresh,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) {
            val refresh = FreshnessRefreshService.getInstance(project)
            for (coordinates in allCoordinates()) {
                refresh.requestLookup(coordinates, force = true)
            }
            rebuild()
        }
    }

    /** A tree row that can jump to its declaration on double-click. */
    private class NavTarget(val file: VirtualFile, val offset: Int, private val label: String) {
        override fun toString(): String = label
    }

    private fun allCoordinates(): Set<Coordinates> =
        collectRows().mapNotNull { row ->
            val g = row.input.declared.groupId ?: return@mapNotNull null
            val a = row.input.declared.artifactId ?: return@mapNotNull null
            Coordinates(g, a)
        }.toSet()

    private class Row(val input: PlannerInput, val file: VirtualFile, val offset: Int)

    private fun collectRows(): List<Row> {
        val lookup = VersionLookupService.getInstance()
        val rows = mutableListOf<Row>()
        for (mavenProject in MavenProjectsManager.getInstance(project).projects) {
            val model = MavenDomUtil.getMavenDomProjectModel(project, mavenProject.file) ?: continue
            for ((dom, declared) in PomDependencyCollector.collectWithDom(model)) {
                val groupId = declared.groupId
                val artifactId = declared.artifactId
                val known = if (groupId != null && artifactId != null) {
                    lookup.peek(Coordinates(groupId, artifactId))?.value
                } else {
                    null
                }
                rows.add(
                    Row(
                        input = PlannerInput(
                            moduleName = mavenProject.displayName,
                            declared = declared,
                            known = known,
                            moduleId = mavenProject.file.path,
                        ),
                        file = mavenProject.file,
                        offset = dom.xmlTag?.textOffset ?: 0,
                    ),
                )
            }
        }
        return rows
    }

    fun rebuild() {
        val settings = StaleguardSettings.getInstance()
        val thresholdMs = TimeUnit.DAYS.toMillis(365L * settings.state.abandonmentYears)
        val now = System.currentTimeMillis()

        val rows = collectRows()
        if (rows.isEmpty()) {
            tree.model = DefaultTreeModel(DefaultMutableTreeNode(StaleguardBundle.message("toolwindow.empty")))
            return
        }

        val inputs = rows.map { it.input }
        val plan = UpgradePlanner.plan(
            inputs, settings.state.suggestPrereleases, thresholdMs, settings::isIgnored, now,
        )
        val stats = StatsCalculator.compute(inputs, plan, thresholdMs, now)
        val summary = StatsCalculator.summary(stats)

        // Positive empty state: an empty-looking tree reads as "broken".
        val allFresh = summary.totalUpdates == 0 && summary.unresolved == 0 && summary.abandoned == 0
        val root = DefaultMutableTreeNode(
            if (allFresh) {
                StaleguardBundle.message("toolwindow.allfresh", summary.totalDependencies)
            } else {
                StaleguardBundle.message(
                    "toolwindow.summary",
                    summary.totalDependencies, summary.totalUpdates, summary.abandoned,
                )
            },
        )

        val unresolvedCoordinates = mutableSetOf<Coordinates>()
        for (moduleStats in stats) {
            val moduleRows = rows.filter { it.input.moduleName == moduleStats.moduleName }
            val moduleNode = DefaultMutableTreeNode(
                StaleguardBundle.message(
                    "toolwindow.module",
                    moduleStats.moduleName, moduleStats.totalDependencies, moduleStats.patchUpdates,
                    moduleStats.minorUpdates, moduleStats.majorUpdates, moduleStats.abandoned,
                ),
            )
            val candidatesByCoords = plan.candidates
                .filter { it.moduleName == moduleStats.moduleName }
                .associateBy { it.coordinates.toString() }
            for (row in moduleRows) {
                val coordinate = row.input.declared.coordinate
                val candidate = candidatesByCoords[coordinate]
                val licenseSuffix = row.input.known?.licenses?.firstOrNull()?.let { license ->
                    val warn = if (com.tampwell.staleguard.repository.PomInfo.isCopyleft(license)) {
                        " " + StaleguardBundle.message("license.copyleft.marker")
                    } else {
                        ""
                    }
                    "  [$license$warn]"
                } ?: ""
                val label = when {
                    candidate != null ->
                        "$coordinate  ${candidate.currentVersion.value} → ${candidate.suggestedVersion.value}" +
                            " (" + StaleguardBundle.message("severity.${candidate.severity.name.lowercase()}") + ")" +
                            licenseSuffix
                    row.input.known == null -> {
                        row.input.declared.groupId?.let { g ->
                            row.input.declared.artifactId?.let { a -> unresolvedCoordinates.add(Coordinates(g, a)) }
                        }
                        "$coordinate  " + StaleguardBundle.message("toolwindow.checking.item")
                    }
                    else -> null // up to date: keep the tree focused on actionable rows
                }
                if (label != null) {
                    moduleNode.add(DefaultMutableTreeNode(NavTarget(row.file, row.offset, label)))
                }
            }
            root.add(moduleNode)
        }

        tree.model = DefaultTreeModel(root)
        for (i in 0 until tree.rowCount) tree.expandRow(i)

        // Opening the window warms the cache for everything unknown.
        val refresh = FreshnessRefreshService.getInstance(project)
        unresolvedCoordinates.forEach { refresh.requestLookup(it) }
    }
}
