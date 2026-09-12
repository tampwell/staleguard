package com.tampwell.staleguard.gradle

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.inspection.VulnerabilityProblems
import com.tampwell.staleguard.repository.Coordinates

/**
 * The one place relock detection happens, so it works no matter which
 * surface scanned first: the status bar recompute (always alive) and the
 * tool window rebuild both feed scans here, [LockHistoryState.update]
 * hands the newly recorded relocks to exactly one caller, and a relock
 * that introduces a known vulnerability warns - the only relock outcome
 * that deserves a sound.
 */
object RelockNotifier {

    fun process(project: Project, entries: List<LockfileScan.Entry>) {
        val newRelocks = LockHistoryState.getInstance(project).update(entries)
        for (relock in newRelocks) {
            val introduced = relock.delta.changed.flatMap { movement ->
                (advisoryIds(project, movement.group, movement.name, movement.to) -
                    advisoryIds(project, movement.group, movement.name, movement.from))
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
                    NotificationType.WARNING,
                )
            }
        }
    }

    private fun advisoryIds(project: Project, group: String, name: String, version: String): Set<String> =
        VulnerabilityProblems.advisoriesFor(project, Coordinates(group, name), version)
            ?.map { it.displayId }?.toSet().orEmpty()
}
