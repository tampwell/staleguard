package com.tampwell.staleguard.services

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.tampwell.staleguard.repository.Coordinates
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bridges the highlighting pass and the lookup engine: the inspection calls
 * [requestLookup] (cheap, non-blocking) for coordinates it couldn't peek; this
 * service resolves them in the background and re-triggers highlighting once —
 * debounced — when new data actually arrived.
 */
@Service(Service.Level.PROJECT)
class FreshnessRefreshService(private val project: Project, private val scope: CoroutineScope) {

    private val log = com.intellij.openapi.diagnostic.logger<FreshnessRefreshService>()

    private val pending = ConcurrentHashMap.newKeySet<Coordinates>()
    private val restartScheduled = AtomicBoolean(false)

    fun requestLookup(coordinates: Coordinates, force: Boolean = false) {
        if (!pending.add(coordinates)) return // already being resolved

        scope.launch {
            val lookupService = VersionLookupService.getInstance()
            val before = lookupService.peek(coordinates)
            try {
                lookupService.lookup(coordinates, force)
            } finally {
                pending.remove(coordinates)
            }
            val after = lookupService.peek(coordinates)
            log.info(
                "Staleguard: resolved $coordinates -> " +
                    when {
                        after == null -> "no result"
                        after.failed -> "FAILED (stale=${after.value != null})"
                        after.value == null -> "not found (404)"
                        else -> "${after.value.versions.size} versions, latest=${after.value.latest?.value}"
                    },
            )
            if (after?.failed == true) {
                failedCoordinates.add(coordinates)
                notifyOfflineOnce()
            } else if (after?.value != null) {
                failedCoordinates.remove(coordinates)
            }
            // Only repaint when the answer changed — a warm-cache confirmation
            // (or a still-failing lookup) must not churn the editor.
            if (after?.value != before?.value) {
                scheduleRestart()
                if (!project.isDisposed) {
                    project.messageBus.syncPublisher(FreshnessListener.TOPIC).freshnessChanged()
                }
            }
        }
    }

    private val pendingVulns = ConcurrentHashMap.newKeySet<com.tampwell.staleguard.security.VulnKey>()
    private val vulnFlushScheduled = AtomicBoolean(false)

    /**
     * Same bridge for vulnerability lookups, but batched: a highlighting pass
     * over a big project enqueues every dependency within milliseconds, so we
     * collect the burst briefly and resolve it as ONE OSV batch request plus
     * detail queries for actual hits, then repaint once.
     */
    fun requestVulnerabilityLookup(coordinates: Coordinates, version: String) {
        pendingVulns.add(com.tampwell.staleguard.security.VulnKey(coordinates, version))
        if (!vulnFlushScheduled.compareAndSet(false, true)) return

        scope.launch {
            delay(VULN_BATCH_DEBOUNCE_MS)
            vulnFlushScheduled.set(false)
            val batch = pendingVulns.toList()
            pendingVulns.removeAll(batch.toSet())
            if (batch.isEmpty()) return@launch

            val result = VulnerabilityService.getInstance().lookupBatch(batch)
            log.info(
                "Staleguard: resolved advisory batch of ${batch.size} " +
                    "(changed=${result.changed}, new=${result.newlyVulnerable.size})",
            )
            if (result.newlyVulnerable.isNotEmpty()) notifyNewAdvisories(result.newlyVulnerable)
            if (result.changed) {
                scheduleRestart()
                if (!project.isDisposed) {
                    project.messageBus.syncPublisher(FreshnessListener.TOPIC).freshnessChanged()
                }
            }
        }
    }

    /**
     * A dependency that was clean at the last check has an advisory now —
     * the one security event worth interrupting for. At most one notification
     * per batch; the editor highlights carry the rest.
     */
    private fun notifyNewAdvisories(
        newly: List<Pair<com.tampwell.staleguard.security.VulnKey, List<com.tampwell.staleguard.security.OsvAdvisory>>>,
    ) {
        if (project.isDisposed) return
        val (firstKey, firstAdvisories) = newly.first()
        val worst = com.tampwell.staleguard.inspection.VulnerabilityProblems.worst(firstAdvisories)
        val severity = worst.severity?.lowercase()
            ?: com.tampwell.staleguard.StaleguardBundle.message("severity.vuln.unknown")
        var message = com.tampwell.staleguard.StaleguardBundle.message(
            "advisory.new.notice",
            "${firstKey.coordinates}:${firstKey.version}",
            worst.displayId,
            severity,
        )
        if (newly.size > 1) {
            message += com.tampwell.staleguard.StaleguardBundle.message("advisory.new.notice.more", newly.size - 1)
        }
        val notification = com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("Staleguard")
            .createNotification(
                com.tampwell.staleguard.StaleguardBundle.message("notification.title"),
                message,
                com.intellij.notification.NotificationType.WARNING,
            )
        notification.addAction(
            com.intellij.notification.NotificationAction.createSimpleExpiring(
                com.tampwell.staleguard.StaleguardBundle.message("advisory.view", worst.displayId),
            ) { com.intellij.ide.BrowserUtil.browse(worst.url) },
        )
        notification.notify(project)
    }

    /** True while any lookup requested through this service is still in flight. */
    fun hasPendingLookups(): Boolean = pending.isNotEmpty() || pendingVulns.isNotEmpty()

    private val offlineNotified = AtomicBoolean(false)
    private val failedCoordinates = ConcurrentHashMap.newKeySet<Coordinates>()

    private val authNotified = AtomicBoolean(false)

    /**
     * A wrong password is not an outage: when the failure is a 401/403 from a
     * credentialed host, say so and point at Settings — once per session.
     */
    private fun notifyAuthFailureOnce(hosts: Set<String>): Boolean {
        if (hosts.isEmpty()) return false
        if (!authNotified.compareAndSet(false, true) || project.isDisposed) return true
        val notification = com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("Staleguard")
            .createNotification(
                com.tampwell.staleguard.StaleguardBundle.message("notification.title"),
                com.tampwell.staleguard.StaleguardBundle.message("auth.failed.notice", hosts.sorted().joinToString(", ")),
                com.intellij.notification.NotificationType.WARNING,
            )
        notification.addAction(
            com.intellij.notification.NotificationAction.createSimpleExpiring(
                com.tampwell.staleguard.StaleguardBundle.message("auth.failed.open.settings"),
            ) {
                authNotified.set(false)
                com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, com.tampwell.staleguard.settings.StaleguardConfigurable::class.java)
            },
        )
        notification.notify(project)
        return true
    }

    /**
     * Offline must be visible, but exactly once per project session — never
     * nag. "Retry Now" bypasses the 5-minute failure throttle for everything
     * that failed, for users who just fixed their proxy/VPN.
     */
    private fun notifyOfflineOnce() {
        if (notifyAuthFailureOnce(VersionLookupService.getInstance().authFailedHosts())) return
        if (!offlineNotified.compareAndSet(false, true) || project.isDisposed) return
        val notification = com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("Staleguard")
            .createNotification(
                com.tampwell.staleguard.StaleguardBundle.message("notification.title"),
                com.tampwell.staleguard.StaleguardBundle.message("offline.notice"),
                com.intellij.notification.NotificationType.WARNING,
            )
        notification.addAction(
            com.intellij.notification.NotificationAction.createSimpleExpiring(
                com.tampwell.staleguard.StaleguardBundle.message("offline.retry"),
            ) {
                offlineNotified.set(false) // re-notify if the retry also fails
                failedCoordinates.toList().forEach { requestLookup(it, force = true) }
            },
        )
        notification.notify(project)
    }

    /**
     * One restart per burst: many dependencies resolving together repaint
     * once. Restarts only the open pom.xml editors (the per-file restart is
     * the supported API; the whole-project restart() is deprecated) — which
     * is also strictly less work for the daemon.
     */
    private fun scheduleRestart() {
        if (!restartScheduled.compareAndSet(false, true)) return
        scope.launch {
            delay(RESTART_DEBOUNCE_MS)
            restartScheduled.set(false)
            withContext(Dispatchers.EDT) {
                if (project.isDisposed) return@withContext
                val daemon = DaemonCodeAnalyzer.getInstance(project)
                val psiManager = PsiManager.getInstance(project)
                FileEditorManager.getInstance(project).openFiles
                    .filter {
                        it.name == "pom.xml" || it.name.endsWith(".gradle") ||
                            it.name.endsWith(".gradle.kts") || it.name.endsWith(".versions.toml")
                    }
                    .mapNotNull(psiManager::findFile)
                    // restart(PsiFile) is the current API on the public 253 line;
                    // the reason-taking overload only exists in the unreleased 262 branch.
                    .forEach { daemon.restart(it) }
            }
        }
    }

    companion object {
        private const val RESTART_DEBOUNCE_MS = 300L

        /** Long enough to catch one highlighting pass's whole burst, short enough to feel instant. */
        private const val VULN_BATCH_DEBOUNCE_MS = 400L

        fun getInstance(project: Project): FreshnessRefreshService = project.service()
    }
}
