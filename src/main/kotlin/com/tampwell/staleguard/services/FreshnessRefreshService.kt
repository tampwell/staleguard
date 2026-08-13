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

    fun requestLookup(coordinates: Coordinates) {
        if (!pending.add(coordinates)) return // already being resolved

        scope.launch {
            val lookupService = VersionLookupService.getInstance()
            val before = lookupService.peek(coordinates)
            try {
                lookupService.lookup(coordinates)
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
            // Only repaint when the answer changed — a warm-cache confirmation
            // (or a still-failing lookup) must not churn the editor.
            if (after?.value != before?.value) {
                scheduleRestart()
            }
        }
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
                    .filter { it.name == "pom.xml" }
                    .mapNotNull(psiManager::findFile)
                    .forEach { daemon.restart(it, "Staleguard: dependency versions resolved") }
            }
        }
    }

    companion object {
        private const val RESTART_DEBOUNCE_MS = 300L

        fun getInstance(project: Project): FreshnessRefreshService = project.service()
    }
}
