package com.tampwell.staleguard.statusbar

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.plan.StatsCalculator
import com.tampwell.staleguard.plan.UpgradePlanner
import com.tampwell.staleguard.services.FreshnessListener
import com.tampwell.staleguard.settings.StaleguardSettings
import com.tampwell.staleguard.toolwindow.BuildFileRows
import java.awt.Component
import java.awt.event.MouseEvent
import java.util.concurrent.TimeUnit

class StaleguardStatusBarFactory : StatusBarWidgetFactory {

    override fun getId(): String = WIDGET_ID

    override fun getDisplayName(): String = StaleguardBundle.message("statusbar.name")

    override fun createWidget(project: Project): StatusBarWidget = StaleguardStatusBarWidget(project)

    companion object {
        const val WIDGET_ID = "StaleguardStatus"
    }
}

/**
 * A quiet count in the status bar: "Deps: 5 updates · 2 abandoned". Empty
 * (invisible) while everything is fresh — the widget earns its pixels only
 * when there is something to act on. Click opens the tool window. Counts are
 * recomputed off the EDT on every freshness event.
 */
class StaleguardStatusBarWidget(private val project: Project) :
    StatusBarWidget, StatusBarWidget.TextPresentation {

    @Volatile
    private var text: String = ""

    @Volatile
    private var statusBar: StatusBar? = null

    override fun ID(): String = StaleguardStatusBarFactory.WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        val connection = project.messageBus.connect(this)
        connection.subscribe(FreshnessListener.TOPIC, FreshnessListener { recompute() })
        connection.subscribe(
            com.tampwell.staleguard.impact.LinkageVerdictListener.TOPIC,
            com.tampwell.staleguard.impact.LinkageVerdictListener { recompute() },
        )
        recompute()
    }

    override fun dispose() {
        statusBar = null
    }

    private fun recompute() {
        ReadAction.nonBlocking<Triple<Int, Int, Int>> {
                val summary = com.tampwell.staleguard.toolwindow.ProjectSummary.compute(project)
                Triple(summary.totalUpdates, summary.abandoned, summary.vulnerable)
            }
            .expireWith(this)
            .finishOnUiThread(com.intellij.openapi.application.ModalityState.any()) { (updates, abandoned, vulnerable) ->
                // Linkage state joins the count: a classpath that will fail at
                // runtime is at least as actionable as an outdated version.
                val verdict = com.tampwell.staleguard.impact.LinkageVerdictState.getInstance(project).current
                val linkage = (verdict?.failing ?: 0) + (verdict?.shadowed ?: 0)
                val base = when {
                    updates == 0 && abandoned == 0 && vulnerable == 0 -> ""
                    vulnerable > 0 ->
                        StaleguardBundle.message("statusbar.text.vulnerable", updates, abandoned, vulnerable)
                    else -> StaleguardBundle.message("statusbar.text", updates, abandoned)
                }
                text = when {
                    linkage == 0 -> base
                    base.isEmpty() -> StaleguardBundle.message("statusbar.text.linkageonly", linkage)
                    else -> base + StaleguardBundle.message("statusbar.text.linkage", linkage)
                }
                statusBar?.updateWidget(ID())
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    override fun getText(): String = text

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getTooltipText(): String = StaleguardBundle.message("statusbar.tooltip")

    override fun getClickConsumer(): com.intellij.util.Consumer<MouseEvent> =
        com.intellij.util.Consumer {
            ToolWindowManager.getInstance(project).getToolWindow("Staleguard")?.show()
        }
}
