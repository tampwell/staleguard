package com.tampwell.staleguard.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.plan.ModuleStats
import com.tampwell.staleguard.plan.StatsCalculator
import com.tampwell.staleguard.plan.UpgradePlan
import com.tampwell.staleguard.plan.UpgradePlanner
import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.repository.PomInfo
import com.tampwell.staleguard.services.FreshnessListener
import com.tampwell.staleguard.services.FreshnessRefreshService
import com.tampwell.staleguard.settings.StaleguardSettings
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * The Staleguard tool window: project summary, per-module freshness stats,
 * and navigable upgrade entries. Reads only the warm cache; opening the
 * window enqueues background lookups for anything unresolved, and the
 * message-bus [FreshnessListener] rebuilds the view as answers arrive.
 *
 * Collection walks the Maven DOM and the file index, so it runs as a
 * non-blocking read action off the EDT; only the finished tree model is
 * applied on the UI thread.
 */
class StaleguardStatsPanel(private val project: Project) :
    SimpleToolWindowPanel(true, true), Disposable {

    private val tree = Tree()

    @Volatile
    private var lastSnapshot: Snapshot? = null

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

    private class Snapshot(
        val rows: List<BuildFileRows.Entry>,
        val plan: UpgradePlan,
        val stats: List<ModuleStats>,
        val summary: ModuleStats,
    )

    fun rebuild() {
        ReadAction.nonBlocking<Snapshot> { computeSnapshot() }
            .expireWith(this)
            .finishOnUiThread(ModalityState.any()) { snapshot ->
                lastSnapshot = snapshot
                applySnapshot(snapshot)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun computeSnapshot(): Snapshot {
        val settings = StaleguardSettings.getInstance()
        val thresholdMs = TimeUnit.DAYS.toMillis(365L * settings.state.abandonmentYears)
        val now = System.currentTimeMillis()

        val rows = BuildFileRows.collect(project)
        val inputs = rows.map { it.input }
        val policy = com.tampwell.staleguard.policy.ProjectPolicyService.getInstance(project)
        val plan = UpgradePlanner.plan(
            inputs, settings.state.suggestPrereleases, thresholdMs, policy::isIgnored, now,
            versionAllowed = policy::versionAllowed,
        )
        val stats = StatsCalculator.compute(
            inputs, plan, thresholdMs, now,
            com.tampwell.staleguard.services.VulnerabilityService.getInstance().advisoryCounter(),
        )
        return Snapshot(rows, plan, stats, StatsCalculator.summary(stats))
    }

    private fun applySnapshot(snapshot: Snapshot) {
        val rows = snapshot.rows
        if (rows.isEmpty()) {
            tree.model = DefaultTreeModel(DefaultMutableTreeNode(StaleguardBundle.message("toolwindow.empty")))
            return
        }
        val plan = snapshot.plan
        val summary = snapshot.summary

        // Positive empty state: an empty-looking tree reads as "broken".
        val allFresh = summary.totalUpdates == 0 && summary.unresolved == 0 &&
            summary.abandoned == 0 && summary.vulnerable == 0
        val root = DefaultMutableTreeNode(
            when {
                allFresh -> StaleguardBundle.message("toolwindow.allfresh", summary.totalDependencies)
                summary.vulnerable > 0 -> StaleguardBundle.message(
                    "toolwindow.summary.vulnerable",
                    summary.totalDependencies, summary.totalUpdates, summary.abandoned, summary.vulnerable,
                )
                else -> StaleguardBundle.message(
                    "toolwindow.summary",
                    summary.totalDependencies, summary.totalUpdates, summary.abandoned,
                )
            },
        )

        val unresolvedCoordinates = mutableSetOf<Coordinates>()
        for (moduleStats in snapshot.stats) {
            val moduleRows = rows.filter { it.input.moduleName == moduleStats.moduleName }
            val moduleNode = DefaultMutableTreeNode(
                if (moduleStats.vulnerable > 0) {
                    StaleguardBundle.message(
                        "toolwindow.module.vulnerable",
                        moduleStats.moduleName, moduleStats.totalDependencies, moduleStats.patchUpdates,
                        moduleStats.minorUpdates, moduleStats.majorUpdates, moduleStats.abandoned,
                        moduleStats.vulnerable,
                    )
                } else {
                    StaleguardBundle.message(
                        "toolwindow.module",
                        moduleStats.moduleName, moduleStats.totalDependencies, moduleStats.patchUpdates,
                        moduleStats.minorUpdates, moduleStats.majorUpdates, moduleStats.abandoned,
                    )
                },
            )
            val candidatesByCoords = plan.candidates
                .filter { it.moduleName == moduleStats.moduleName }
                .associateBy { it.coordinates.toString() }
            for (row in moduleRows) {
                val coordinate = row.input.declared.coordinate
                val candidate = candidatesByCoords[coordinate]
                val licenseSuffix = row.input.known?.licenses?.firstOrNull()?.let { license ->
                    val warn = if (PomInfo.isCopyleft(license)) {
                        " " + StaleguardBundle.message("license.copyleft.marker")
                    } else {
                        ""
                    }
                    "  [$license$warn]"
                } ?: ""
                // A working pin silently capping suggestions reads as a bug —
                // label it so "why no 3.x hint?" answers itself.
                val pinSuffix = row.input.declared.groupId?.let { g ->
                    row.input.declared.artifactId?.let { a ->
                        if (com.tampwell.staleguard.policy.ProjectPolicyService.getInstance(project).hasPin(g, a)) {
                            "  " + StaleguardBundle.message("toolwindow.pinned")
                        } else {
                            ""
                        }
                    }
                } ?: ""
                val label = when {
                    candidate != null ->
                        "$coordinate  ${candidate.currentVersion.value} → ${candidate.suggestedVersion.value}" +
                            " (" + StaleguardBundle.message("severity.${candidate.severity.name.lowercase()}") + ")" +
                            pinSuffix + licenseSuffix
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

    private fun buildToolbar(): JComponent {
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("StaleguardStats", DefaultActionGroup(RefreshAllAction(), ExportAction()), true)
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
            val snapshot = lastSnapshot ?: return
            val refresh = FreshnessRefreshService.getInstance(project)
            snapshot.rows
                .mapNotNull { row ->
                    val g = row.input.declared.groupId ?: return@mapNotNull null
                    val a = row.input.declared.artifactId ?: return@mapNotNull null
                    Coordinates(g, a)
                }
                .toSet()
                .forEach { refresh.requestLookup(it, force = true) }
            rebuild()
        }
    }

    private inner class ExportAction : AnAction(
        StaleguardBundle.message("toolwindow.export"),
        null,
        AllIcons.ToolbarDecorator.Export,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = lastSnapshot != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val snapshot = lastSnapshot ?: return
            val exportRows = snapshot.plan.candidates.map { candidate ->
                val known = snapshot.rows
                    .firstOrNull { it.input.declared.coordinate == candidate.coordinates.toString() }
                    ?.input?.known
                ReportExporter.Row(
                    module = candidate.moduleName,
                    coordinate = candidate.coordinates.toString(),
                    currentVersion = candidate.currentVersion.value,
                    suggestedVersion = candidate.suggestedVersion.value,
                    severity = StaleguardBundle.message("severity.${candidate.severity.name.lowercase()}"),
                    license = known?.licenses?.firstOrNull().orEmpty(),
                    advisories = com.tampwell.staleguard.services.VulnerabilityService.getInstance()
                        .peek(candidate.coordinates, candidate.currentVersion.value)
                        ?.advisories.orEmpty()
                        .sortedByDescending { it.severityRank }
                        .joinToString(" ") { it.displayId },
                )
            }

            val descriptor = FileSaverDescriptor(StaleguardBundle.message("toolwindow.export"), "", "md", "csv")
            val wrapper = FileChooserFactory.getInstance()
                .createSaveFileDialog(descriptor, project)
                .save("staleguard-report.md")
                ?: return
            val content = if (wrapper.file.extension == "csv") {
                ReportExporter.csv(exportRows)
            } else {
                ReportExporter.markdown(project.name, exportRows)
            }
            Files.writeString(wrapper.file.toPath(), content)
        }
    }

    /** A tree row that can jump to its declaration on double-click. */
    private class NavTarget(val file: VirtualFile, val offset: Int, private val label: String) {
        override fun toString(): String = label
    }
}
