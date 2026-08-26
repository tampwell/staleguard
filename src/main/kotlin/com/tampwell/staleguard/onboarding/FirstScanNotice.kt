package com.tampwell.staleguard.onboarding

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.plan.ModuleStats
import com.tampwell.staleguard.services.FreshnessListener
import com.tampwell.staleguard.services.FreshnessRefreshService
import com.tampwell.staleguard.toolwindow.ProjectSummary
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tells the user, once per project, that the first scan finished and found
 * nothing. See [FirstScanVerdict] for why silence is the problem being
 * solved. Findings need no notification — they are already on screen — and
 * the flag is set either way so this never fires twice for a project.
 */
@Service(Service.Level.PROJECT)
internal class FirstScanNotice(private val project: Project) : Disposable {

    /** Subscribed once. */
    private val started = AtomicBoolean(false)

    /** Verdict reached; no further evaluation needed this session. */
    private val settled = AtomicBoolean(false)

    fun start() {
        if (alreadyHandled()) return
        if (!started.compareAndSet(false, true)) return
        project.messageBus.connect(this)
            .subscribe(FreshnessListener.TOPIC, FreshnessListener { evaluate() })
        evaluate()
    }

    /** Bounded so an unresolvable project cannot cost a summary per event forever. */
    private val attempts = AtomicInteger(0)

    private fun evaluate() {
        if (settled.get() || project.isDisposed || alreadyHandled()) return
        if (FreshnessRefreshService.getInstance(project).hasPendingLookups()) return
        // Offline, or a private repository with no credentials yet, leaves
        // coordinates unresolved indefinitely — and the verdict below refuses
        // to claim "everything is current" on incomplete data, so this would
        // recompute the whole project summary on every freshness event with
        // no end. Give up after a few tries. Deliberately NOT persisted: the
        // next session gets a clean shot once the network or credentials are
        // sorted out.
        if (attempts.incrementAndGet() > MAX_ATTEMPTS) {
            settled.set(true)
            return
        }

        ReadAction.nonBlocking<ModuleStats> { ProjectSummary.compute(project) }
            .expireWith(this)
            .finishOnUiThread(ModalityState.any()) { summary ->
                val pending = FreshnessRefreshService.getInstance(project).hasPendingLookups()
                when (FirstScanVerdict.of(summary, pending)) {
                    FirstScanVerdict.Verdict.WAIT -> Unit
                    FirstScanVerdict.Verdict.STAY_SILENT -> markHandled()
                    FirstScanVerdict.Verdict.NOTIFY_CLEAN -> {
                        markHandled()
                        notifyClean(summary.totalDependencies)
                    }
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun notifyClean(dependencyCount: Int) {
        if (project.isDisposed) return
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Staleguard")
            .createNotification(
                StaleguardBundle.message("notification.title"),
                StaleguardBundle.message("onboarding.allcurrent", dependencyCount),
                NotificationType.INFORMATION,
            )
        notification.addAction(
            NotificationAction.createSimpleExpiring(StaleguardBundle.message("onboarding.allcurrent.open")) {
                ToolWindowManager.getInstance(project).getToolWindow("Staleguard")?.activate(null)
            },
        )
        notification.notify(project)
    }

    private fun alreadyHandled(): Boolean = PropertiesComponent.getInstance(project).getBoolean(FLAG, false)

    private fun markHandled() {
        settled.set(true)
        PropertiesComponent.getInstance(project).setValue(FLAG, true)
    }

    override fun dispose() = Unit

    companion object {
        /** Enough to cover a normal warm-up burst, small enough to stay cheap. */
        private const val MAX_ATTEMPTS = 10

        private const val FLAG = "staleguard.first.scan.reported"

        fun getInstance(project: Project): FirstScanNotice = project.service()
    }
}
