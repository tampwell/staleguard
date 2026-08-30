package com.tampwell.staleguard.impact

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.util.Alarm
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.settings.StaleguardSettings
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Turns the classpath doctor from a tool you remember to run into one that
 * catches the conflict the moment a sync introduces it.
 *
 * Listens for module root changes — the one platform event every build
 * system's sync produces — debounced, because a sync fires it in bursts. The
 * audit it runs is LOCAL ONLY (scans and resolution; fix probes download jars
 * and automatic work must never surprise the network), and the notification
 * policy is baseline-then-delta: the first audit establishes what already
 * holds, silently, and only findings absent last time make a sound. Old
 * findings never nag; a cleared classpath clears the baseline quietly.
 */
@Service(Service.Level.PROJECT)
class LinkageWatchService(private val project: Project) : Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val running = AtomicBoolean(false)

    @Volatile
    private var baseline: Set<LinkageDelta.Key>? = null

    fun start() {
        project.messageBus.connect(this).subscribe(
            ModuleRootListener.TOPIC,
            object : ModuleRootListener {
                override fun rootsChanged(event: ModuleRootEvent) {
                    if (!StaleguardSettings.getInstance().state.watchClasspath) return
                    // A sync fires root changes in bursts; only the settled
                    // state is worth auditing.
                    alarm.cancelAllRequests()
                    alarm.addRequest({ runQuietAudit() }, DEBOUNCE_MS)
                }
            },
        )
    }

    private fun runQuietAudit() {
        if (!running.compareAndSet(false, true)) return
        DumbService.getInstance(project).runWhenSmart {
            object : Task.Backgroundable(project, StaleguardBundle.message("linkage.watch.progress"), false) {
                private var result: ClasspathLinkageService.Result? = null

                override fun run(indicator: ProgressIndicator) {
                    result = ClasspathLinkageService.getInstance(project)
                        .audit(indicator, computeSuggestions = false)
                }

                override fun onFinished() {
                    running.set(false)
                }

                override fun onSuccess() {
                    val report = result?.report ?: return
                    val previous = baseline
                    baseline = LinkageDelta.fingerprint(report)
                    if (previous == null) return // discovery is not news
                    val delta = LinkageDelta.newSince(previous, report)
                    if (delta.isNews) notify(delta)
                }
            }.queue()
        }
    }

    private fun notify(delta: LinkageDelta.Delta) {
        // Broken calls and latent shadowing are different news; a shadow-only
        // delta must not claim calls will fail.
        val failing = delta.newBroken.size + delta.newEvicted.size
        val content = listOfNotNull(
            StaleguardBundle.message("linkage.watch.news", failing).takeIf { failing > 0 },
            StaleguardBundle.message("linkage.watch.news.shadow", delta.newShadowed.size)
                .takeIf { delta.newShadowed.isNotEmpty() },
        ).joinToString(" ")
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Staleguard")
            .createNotification(
                StaleguardBundle.message("notification.title"),
                content,
                NotificationType.WARNING,
            )
            .addAction(
                com.intellij.notification.NotificationAction.createSimpleExpiring(
                    StaleguardBundle.message("linkage.watch.show"),
                ) {
                    object : Task.Backgroundable(project, StaleguardBundle.message("linkage.progress"), true) {
                        private var full: ClasspathLinkageService.Result? = null

                        override fun run(indicator: ProgressIndicator) {
                            full = ClasspathLinkageService.getInstance(project).audit(indicator)
                        }

                        override fun onSuccess() {
                            full?.let {
                                LinkageDialog(
                                    project,
                                    it.report,
                                    it.ownCode,
                                    it.suggestions,
                                    it.moduleCount,
                                    it.findingModules,
                                ).show()
                            }
                        }
                    }.queue()
                },
            )
            .notify(project)
    }

    override fun dispose() = Unit

    companion object {
        fun getInstance(project: Project): LinkageWatchService = project.service()

        const val DEBOUNCE_MS = 3_000
    }
}
