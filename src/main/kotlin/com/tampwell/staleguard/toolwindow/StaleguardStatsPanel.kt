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
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.StaleguardVersion
import com.tampwell.staleguard.plan.ModuleStats
import com.tampwell.staleguard.plan.StatsCalculator
import com.tampwell.staleguard.plan.UpgradePlan
import com.tampwell.staleguard.plan.UpgradePlanner
import com.tampwell.staleguard.report.CycloneDxWriter
import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.repository.PomInfo
import com.tampwell.staleguard.services.FreshnessListener
import com.tampwell.staleguard.services.FreshnessRefreshService
import com.tampwell.staleguard.settings.StaleguardSettings
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.util.UUID
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

        val connection = project.messageBus.connect(this)
        connection.subscribe(FreshnessListener.TOPIC, FreshnessListener { SwingUtilities.invokeLater { rebuild() } })
        connection.subscribe(
            com.tampwell.staleguard.impact.LinkageVerdictListener.TOPIC,
            com.tampwell.staleguard.impact.LinkageVerdictListener { SwingUtilities.invokeLater { rebuild() } },
        )

        rebuild()
    }

    override fun dispose() = Unit

    private class Snapshot(
        val rows: List<BuildFileRows.Entry>,
        val plan: UpgradePlan,
        val stats: List<ModuleStats>,
        val summary: ModuleStats,
        val transitiveVulns: List<TransitiveVulnRow> = emptyList(),
        val lockDrifts: List<LockDriftRow> = emptyList(),
        val lockedVulns: List<LockedVulnRow> = emptyList(),
        val relockRows: List<RelockRow> = emptyList(),
    )

    private class RelockRow(val label: String, val file: VirtualFile?)

    private class TransitiveVulnRow(val coordinate: String, val advisoryLine: String, val via: String)

    private class LockDriftRow(
        val coordinate: String,
        val lockedVersion: String,
        val declaredVersion: String,
        val file: VirtualFile?,
        val offset: Int,
    )

    private class LockedVulnRow(
        val coordinate: String,
        val advisoryLine: String,
        val configurations: String,
        val file: VirtualFile?,
        val offset: Int,
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
            measuredImpact = com.tampwell.staleguard.impact.ImpactMemory.getInstance(project).lookup(),
        )
        val stats = StatsCalculator.compute(
            inputs, plan, thresholdMs, now,
            com.tampwell.staleguard.services.VulnerabilityService.getInstance().advisoryCounter(),
        )
        // Transitive CVE sweep: every artifact the resolved dependency trees
        // (Maven and Gradle) pull in beyond the declared ones, checked against
        // the same warm OSV cache the inspections use. Unknowns enqueue and
        // repaint later.
        val transitiveVulns = com.tampwell.staleguard.impact.TransitiveVulnScan
            .candidates(com.tampwell.staleguard.impact.Provenance.nodesFor(project))
            .mapNotNull { candidate ->
                val advisories = com.tampwell.staleguard.inspection.VulnerabilityProblems.advisoriesFor(
                    project,
                    Coordinates(candidate.groupId, candidate.artifactId),
                    candidate.version,
                )
                advisories?.takeIf { it.isNotEmpty() }?.let { found ->
                    val worst = com.tampwell.staleguard.inspection.VulnerabilityProblems.worst(found)
                    TransitiveVulnRow(
                        coordinate = "${candidate.groupId}:${candidate.artifactId}:${candidate.version}",
                        advisoryLine = "${worst.displayId} (${worst.severity?.lowercase() ?: StaleguardBundle.message("severity.vuln.unknown")})",
                        via = candidate.via,
                    )
                }
            }
        // Gradle lockfiles: what dependency locking actually pinned. Drift
        // compares each lock against the declarations of the build file in
        // the same directory; the vulnerability check runs on the LOCKED
        // versions, the most exact input OSV can get. Anything the main
        // table or the transitive sweep already reports is not repeated.
        val lockEntries = com.tampwell.staleguard.gradle.LockfileScan.collect(project)
        var lockDrifts = emptyList<LockDriftRow>()
        var lockedVulns = emptyList<LockedVulnRow>()
        if (lockEntries.isNotEmpty()) {
            val declaredVersions = inputs.mapNotNull { input ->
                val groupId = input.declared.groupId ?: return@mapNotNull null
                val artifactId = input.declared.artifactId ?: return@mapNotNull null
                val version = input.declared.resolvedVersion ?: return@mapNotNull null
                "$groupId:$artifactId:$version"
            }
            lockDrifts = com.tampwell.staleguard.gradle.LockfileScan.driftsFor(lockEntries, inputs)
                .map { drift ->
                    // The line that pinned the drifted version - double-click lands on it.
                    val nav = lockEntries.firstNotNullOfOrNull { entry ->
                        if (!entry.located.driftEligible) return@firstNotNullOfOrNull null
                        entry.lines.firstOrNull {
                            it.locked.group == drift.group && it.locked.name == drift.name &&
                                it.locked.version == drift.lockedVersion
                        }?.let { entry.file to it.range.first }
                    }
                    LockDriftRow(
                        "${drift.group}:${drift.name}", drift.lockedVersion, drift.declaredVersion,
                        nav?.first, nav?.second ?: 0,
                    )
                }

            val alreadyReported = transitiveVulns.map { it.coordinate }.toSet() + declaredVersions
            lockedVulns = lockEntries.asSequence()
                .flatMap { entry -> entry.lines.asSequence().map { entry.file to it } }
                .distinctBy { (_, line) -> "${line.locked.group}:${line.locked.name}:${line.locked.version}" }
                .mapNotNull { (file, line) ->
                    val locked = line.locked
                    val coordinate = "${locked.group}:${locked.name}:${locked.version}"
                    if (coordinate in alreadyReported) return@mapNotNull null
                    val advisories = com.tampwell.staleguard.inspection.VulnerabilityProblems.advisoriesFor(
                        project,
                        Coordinates(locked.group, locked.name),
                        locked.version,
                    )
                    advisories?.takeIf { it.isNotEmpty() }?.let { found ->
                        val worst = com.tampwell.staleguard.inspection.VulnerabilityProblems.worst(found)
                        LockedVulnRow(
                            coordinate = coordinate,
                            advisoryLine = "${worst.displayId} (${worst.severity?.lowercase() ?: StaleguardBundle.message("severity.vuln.unknown")})",
                            configurations = locked.configurations.joinToString(", ").ifEmpty { "-" },
                            file = file,
                            offset = line.range.first,
                        )
                    }
                }
                .toList()
        }
        // The relock story: what the most recent lockfile regeneration
        // actually moved, with the advisories it fixed or introduced.
        val history = com.tampwell.staleguard.gradle.LockHistoryState.getInstance(project)
        val newRelocks = history.update(lockEntries)
        val fileByPath = lockEntries.associate { it.file.path.replace('\\', '/') to it.file }
        fun advisoryIds(group: String, name: String, version: String): Set<String> =
            com.tampwell.staleguard.inspection.VulnerabilityProblems
                .advisoriesFor(project, Coordinates(group, name), version)
                ?.map { it.displayId }?.toSet().orEmpty()
        // A relock that introduces a known vulnerability is the one relock
        // outcome that deserves a sound. Baselines advanced with the return
        // value, so a restarted rebuild cannot notify twice.
        for (relock in newRelocks) {
            val introduced = relock.delta.changed.flatMap { movement ->
                (advisoryIds(movement.group, movement.name, movement.to) -
                    advisoryIds(movement.group, movement.name, movement.from))
                    .map { advisory -> "$advisory via ${movement.group}:${movement.name} ${movement.from} -> ${movement.to}" }
            }
            if (introduced.isNotEmpty()) {
                com.tampwell.staleguard.actions.UpgradeApplier.notify(
                    project,
                    StaleguardBundle.message(
                        "notification.relock.introduces",
                        relock.filePath.substringAfterLast('/'),
                        introduced.joinToString("; "),
                    ),
                    com.intellij.notification.NotificationType.WARNING,
                )
            }
        }
        val multipleFiles = history.lastRelocks.size > 1
        val relockRows = history.lastRelocks.flatMap { relock ->
            val file = fileByPath[relock.filePath]
            val prefix = if (multipleFiles) "${relock.filePath.substringAfterLast('/')}: " else ""
            relock.delta.changed.map { movement ->
                val fromIds = advisoryIds(movement.group, movement.name, movement.from)
                val toIds = advisoryIds(movement.group, movement.name, movement.to)
                val fixed = fromIds - toIds
                val introduced = toIds - fromIds
                val suffix = buildString {
                    if (fixed.isNotEmpty()) append(StaleguardBundle.message("toolwindow.relock.fixes", fixed.joinToString(", ")))
                    if (introduced.isNotEmpty()) append(StaleguardBundle.message("toolwindow.relock.introduces", introduced.joinToString(", ")))
                }
                RelockRow(
                    prefix + StaleguardBundle.message(
                        "toolwindow.relock.changed.row",
                        "${movement.group}:${movement.name}", movement.from, movement.to, suffix,
                    ),
                    file,
                )
            } + relock.delta.added.map { locked ->
                RelockRow(
                    prefix + StaleguardBundle.message(
                        "toolwindow.relock.added.row", "${locked.group}:${locked.name}", locked.version,
                    ),
                    file,
                )
            } + relock.delta.removed.map { locked ->
                RelockRow(
                    prefix + StaleguardBundle.message(
                        "toolwindow.relock.removed.row", "${locked.group}:${locked.name}", locked.version,
                    ),
                    file,
                )
            }
        }.take(RELOCK_ROW_CAP)
        return Snapshot(
            rows, plan, stats, StatsCalculator.summary(stats),
            transitiveVulns, lockDrifts, lockedVulns, relockRows,
        )
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
        val summaryText = when {
            allFresh -> StaleguardBundle.message("toolwindow.allfresh", summary.totalDependencies)
            summary.vulnerable > 0 -> StaleguardBundle.message(
                "toolwindow.summary.vulnerable",
                summary.totalDependencies, summary.totalUpdates, summary.abandoned, summary.vulnerable,
            )
            else -> StaleguardBundle.message(
                "toolwindow.summary",
                summary.totalDependencies, summary.totalUpdates, summary.abandoned,
            )
        }
        // Drift belongs in the headline: "all fresh" with a stale lock would
        // be a false all-clear, since the build runs the lock, not the files.
        val root = DefaultMutableTreeNode(
            if (snapshot.lockDrifts.isEmpty()) {
                summaryText
            } else {
                summaryText + StaleguardBundle.message("toolwindow.summary.lockdrift", snapshot.lockDrifts.size)
            },
        )

        // The doctor's last verdict rides along, so the classpath's health is
        // visible without running anything. Absent until the first audit.
        com.tampwell.staleguard.impact.LinkageVerdictState.getInstance(project).current?.let { verdict ->
            root.add(
                DefaultMutableTreeNode(
                    when {
                        verdict.clean -> StaleguardBundle.message("toolwindow.linkage.clean")
                        verdict.shadowed == 0 -> StaleguardBundle.message("toolwindow.linkage.failing", verdict.failing)
                        verdict.failing == 0 -> StaleguardBundle.message("toolwindow.linkage.shadowed", verdict.shadowed)
                        else -> StaleguardBundle.message("toolwindow.linkage.both", verdict.failing, verdict.shadowed)
                    },
                ),
            )
        }

        if (snapshot.transitiveVulns.isNotEmpty()) {
            val vulnNode = DefaultMutableTreeNode(
                StaleguardBundle.message("toolwindow.transitive.vulns", snapshot.transitiveVulns.size),
            )
            for (row in snapshot.transitiveVulns) {
                vulnNode.add(
                    DefaultMutableTreeNode(
                        StaleguardBundle.message(
                            "toolwindow.transitive.vuln.row",
                            row.coordinate, row.advisoryLine, row.via,
                        ),
                    ),
                )
            }
            root.add(vulnNode)
        }

        if (snapshot.lockDrifts.isNotEmpty()) {
            val driftNode = DefaultMutableTreeNode(
                StaleguardBundle.message("toolwindow.lockfile.drift", snapshot.lockDrifts.size),
            )
            for (row in snapshot.lockDrifts) {
                val label = StaleguardBundle.message(
                    "toolwindow.lockfile.drift.row",
                    row.coordinate, row.lockedVersion, row.declaredVersion,
                )
                driftNode.add(
                    DefaultMutableTreeNode(row.file?.let { NavTarget(it, row.offset, label) } ?: label),
                )
            }
            root.add(driftNode)
        }

        if (snapshot.relockRows.isNotEmpty()) {
            val relockNode = DefaultMutableTreeNode(
                StaleguardBundle.message("toolwindow.relock", snapshot.relockRows.size),
            )
            for (row in snapshot.relockRows) {
                relockNode.add(
                    DefaultMutableTreeNode(row.file?.let { NavTarget(it, 0, row.label) } ?: row.label),
                )
            }
            root.add(relockNode)
        }

        if (snapshot.lockedVulns.isNotEmpty()) {
            val lockedNode = DefaultMutableTreeNode(
                StaleguardBundle.message("toolwindow.lockfile.vulns", snapshot.lockedVulns.size),
            )
            for (row in snapshot.lockedVulns) {
                val label = StaleguardBundle.message(
                    "toolwindow.lockfile.vuln.row",
                    row.coordinate, row.advisoryLine, row.configurations,
                )
                lockedNode.add(
                    DefaultMutableTreeNode(row.file?.let { NavTarget(it, row.offset, label) } ?: label),
                )
            }
            root.add(lockedNode)
        }

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
                val coordinateKey = row.input.declared.coordinate
                val candidate = candidatesByCoords[coordinateKey]
                // Display form only — plan lookups stay keyed on the raw coordinate.
                val coordinate = row.input.declared.artifactId
                    ?.takeIf { it == "${row.input.declared.groupId}.gradle.plugin" }
                    ?.let { "${row.input.declared.groupId} (plugin)" }
                    ?: coordinateKey
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
                val declaredVersion = row.input.declared.resolvedVersion
                val advisories = row.input.declared.groupId?.let { g ->
                    row.input.declared.artifactId?.let { a ->
                        declaredVersion?.let { v ->
                            com.tampwell.staleguard.services.VulnerabilityService.getInstance()
                                .peek(Coordinates(g, a), v)?.advisories
                        }
                    }
                }.orEmpty()
                val advisorySuffix = if (advisories.isNotEmpty()) {
                    "  ⚠ " + com.tampwell.staleguard.inspection.VulnerabilityProblems.worst(advisories).displayId
                } else {
                    ""
                }
                // The tool window must agree with the editor: once an impact
                // check ran for this exact pair, the row says what it found.
                val measuredSuffix = candidate?.let {
                    when (val m = com.tampwell.staleguard.impact.ImpactMemory.getInstance(project)
                        .measured(it.coordinates.toString(), it.currentVersion.value, it.suggestedVersion.value)) {
                        is com.tampwell.staleguard.plan.MeasuredImpact.Breaks ->
                            "  " + StaleguardBundle.message("toolwindow.impact.breaks", m.members)
                        is com.tampwell.staleguard.plan.MeasuredImpact.BreaksLinkage ->
                            "  " + StaleguardBundle.message("toolwindow.impact.linkage", m.problems)
                        com.tampwell.staleguard.plan.MeasuredImpact.Clean ->
                            "  " + StaleguardBundle.message("toolwindow.impact.clean")
                        com.tampwell.staleguard.plan.MeasuredImpact.Unknown -> ""
                    }
                } ?: ""
                val label = when {
                    candidate != null ->
                        "$coordinate  ${candidate.currentVersion.value} → ${candidate.suggestedVersion.value}" +
                            " (" + StaleguardBundle.message("severity.${candidate.severity.name.lowercase()}") + ")" +
                            measuredSuffix + advisorySuffix + pinSuffix + licenseSuffix
                    row.input.known == null -> {
                        row.input.declared.groupId?.let { g ->
                            row.input.declared.artifactId?.let { a -> unresolvedCoordinates.add(Coordinates(g, a)) }
                        }
                        "$coordinate  " + StaleguardBundle.message("toolwindow.checking.item")
                    }
                    // Up to date but carrying a known advisory: the module tally
                    // counts it, so hiding the row made the count unexplainable.
                    advisories.isNotEmpty() ->
                        "$coordinate  $declaredVersion$advisorySuffix$licenseSuffix"
                    else -> null // up to date and clean: keep the tree focused on actionable rows
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
            .createActionToolbar(
                "StaleguardStats",
                DefaultActionGroup(RefreshAllAction(), ExportAction(), SbomExportAction(), CheckClasspathAction(), ReportIssueAction()),
                true,
            )
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

    private inner class SbomExportAction : AnAction(
        StaleguardBundle.message("toolwindow.export.sbom"),
        null,
        AllIcons.Actions.Download,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = lastSnapshot != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val snapshot = lastSnapshot ?: return
            // Collection reads indexes and walks resolved trees - off the
            // EDT; only the save dialog and the file write come back to it.
            ReadAction.nonBlocking<SbomPayload> { collectPayload(snapshot) }
                .expireWith(this@StaleguardStatsPanel)
                .finishOnUiThread(ModalityState.defaultModalityState()) { payload -> saveSbom(payload) }
                .submit(AppExecutorUtil.getAppExecutorService())
        }

        private fun collectPayload(snapshot: Snapshot): SbomPayload {
            val vulnerabilities = com.tampwell.staleguard.services.VulnerabilityService.getInstance()
            val lookup = com.tampwell.staleguard.services.VersionLookupService.getInstance()

            // The whole truth: every artifact the resolved trees ship, with
            // lockfile-pinned versions winning over the graph's answer. The
            // declared rows ride along as a superset guarantee for build
            // files whose resolved graph is not available.
            val locked = com.tampwell.staleguard.gradle.LockfileScan.collect(project).flatMap { it.locked }
            val graph = com.tampwell.staleguard.report.SbomGraph.collect(
                com.tampwell.staleguard.impact.Provenance.nodesFor(project),
                locked,
            )
            val graphComponents = graph.artifacts.map { artifact ->
                CycloneDxWriter.Component(
                    groupId = artifact.groupId,
                    artifactId = artifact.artifactId,
                    version = artifact.version,
                    licenses = lookup.peek(Coordinates(artifact.groupId, artifact.artifactId))
                        ?.value?.licenses.orEmpty(),
                    advisories = vulnerabilities.peek(Coordinates(artifact.groupId, artifact.artifactId), artifact.version)
                        ?.advisories.orEmpty(),
                    properties = artifact.graphVersion
                        ?.let { listOf("staleguard:graph-version" to it) }
                        .orEmpty(),
                )
            }
            // The graph is authoritative for every coordinate it knows: a
            // declared version that mediation replaced must NOT ride along as
            // a second component, so the exclusion is by coordinate, not by
            // exact version.
            val graphCoordinates = graph.artifacts.map { it.groupId to it.artifactId }.toSet()
            val declaredExtra = snapshot.rows.mapNotNull { row ->
                val declared = row.input.declared
                val groupId = declared.groupId ?: return@mapNotNull null
                val artifactId = declared.artifactId ?: return@mapNotNull null
                val version = declared.resolvedVersion ?: return@mapNotNull null
                if (groupId to artifactId in graphCoordinates) return@mapNotNull null
                CycloneDxWriter.Component(
                    groupId = groupId,
                    artifactId = artifactId,
                    version = version,
                    licenses = row.input.known?.licenses.orEmpty(),
                    advisories = vulnerabilities.peek(Coordinates(groupId, artifactId), version)
                        ?.advisories.orEmpty(),
                )
            }
            val components = graphComponents + declaredExtra
            val purlByKey = graph.artifacts.zip(graphComponents).associate { (artifact, component) ->
                artifact.key to component.purl
            }
            val dependsOn = graph.dependsOn.entries.mapNotNull { (key, children) ->
                purlByKey[key]?.let { it to children.mapNotNull(purlByKey::get) }
            }.toMap()
            val rootDependsOn = graph.rootDependsOn.mapNotNull(purlByKey::get) + declaredExtra.map { it.purl }
            return SbomPayload(components, dependsOn, rootDependsOn)
        }

        private fun saveSbom(payload: SbomPayload) {
            if (payload.components.isEmpty()) {
                Messages.showInfoMessage(
                    StaleguardBundle.message("sbom.nothing"),
                    StaleguardBundle.message("toolwindow.export.sbom"),
                )
                return
            }

            val descriptor = FileSaverDescriptor(StaleguardBundle.message("toolwindow.export.sbom"), "", *arrayOf("json"))
            val wrapper = FileChooserFactory.getInstance()
                .createSaveFileDialog(descriptor, project)
                .save("${project.name}-sbom.cdx.json")
                ?: return
            val content = CycloneDxWriter.write(
                projectName = project.name,
                toolVersion = StaleguardVersion.current(),
                components = payload.components,
                serialUuid = UUID.randomUUID().toString(),
                timestampMillis = System.currentTimeMillis(),
                dependsOn = payload.dependsOn,
                rootDependsOn = payload.rootDependsOn,
            )
            Files.writeString(wrapper.file.toPath(), content)
        }
    }

    private class SbomPayload(
        val components: List<CycloneDxWriter.Component>,
        val dependsOn: Map<String, List<String>>,
        val rootDependsOn: List<String>,
    )

    /** A tree row that can jump to its declaration on double-click. */
    private class NavTarget(val file: VirtualFile, val offset: Int, private val label: String) {
        override fun toString(): String = label
    }

    private companion object {
        /** A giant relock stays readable: the newest movements speak for the rest. */
        const val RELOCK_ROW_CAP = 25
    }
}
