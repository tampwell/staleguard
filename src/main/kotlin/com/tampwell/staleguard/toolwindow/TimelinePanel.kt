package com.tampwell.staleguard.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.plan.AgeBucket
import com.tampwell.staleguard.plan.PlannerInput
import com.tampwell.staleguard.plan.TimelineEntry
import com.tampwell.staleguard.plan.TimelineModel
import com.tampwell.staleguard.plan.UpgradePlanner
import com.tampwell.staleguard.services.FreshnessListener
import com.tampwell.staleguard.settings.StaleguardSettings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * "Dependency Age Timeline": one bar per dependency from its newest release
 * date to today, over a 5-year axis. Data comes entirely from the warm cache
 * — zero network. Colors are theme-aware (JBColor). PNG snapshot for team
 * reports via the toolbar.
 */
class TimelinePanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val chart = ChartComponent()
    private val legend = buildLegend()

    init {
        toolbar = buildToolbar()
        val content = JPanel(BorderLayout())
        content.add(legend, BorderLayout.NORTH)
        content.add(JBScrollPane(chart), BorderLayout.CENTER)
        setContent(content)

        project.messageBus.connect(this)
            .subscribe(FreshnessListener.TOPIC, FreshnessListener { SwingUtilities.invokeLater { rebuild() } })
        rebuild()
    }

    override fun dispose() = Unit

    fun rebuild() {
        val settings = StaleguardSettings.getInstance()
        val now = System.currentTimeMillis()
        val thresholdMs = TimeUnit.DAYS.toMillis(365L * settings.state.abandonmentYears)

        // Collection walks the Maven DOM and file index — same off-EDT rule
        // as the statistics panel.
        com.intellij.openapi.application.ReadAction
            .nonBlocking<List<PlannerInput>> { BuildFileRows.collect(project).map { it.input } }
            .expireWith(this)
            .finishOnUiThread(com.intellij.openapi.application.ModalityState.any()) { inputs ->
                val plan = UpgradePlanner.plan(inputs, settings.state.suggestPrereleases, thresholdMs, com.tampwell.staleguard.policy.ProjectPolicyService.getInstance(project)::isIgnored, now)
                chart.update(
                    TimelineModel.build(
                        inputs, plan, now,
                        com.tampwell.staleguard.services.VulnerabilityService.getInstance().advisoryCounter(),
                    ),
                    now,
                )
            }
            .submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService())
    }

    private fun buildToolbar(): JComponent {
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("StaleguardTimeline", DefaultActionGroup(SnapshotAction()), true)
        toolbar.targetComponent = this
        return toolbar.component
    }

    private fun buildLegend(): JComponent {
        val panel = JPanel()
        fun swatch(color: Color, key: String) {
            panel.add(
                JBLabel(StaleguardBundle.message(key)).apply {
                    icon = com.intellij.util.ui.ColorIcon(10, color)
                    border = JBUI.Borders.emptyRight(12)
                },
            )
        }
        swatch(FRESH_COLOR, "timeline.legend.fresh")
        swatch(AGING_COLOR, "timeline.legend.aging")
        swatch(STALE_COLOR, "timeline.legend.stale")
        swatch(UNKNOWN_COLOR, "timeline.legend.unknown")
        return panel
    }

    private inner class SnapshotAction : AnAction(
        StaleguardBundle.message("timeline.snapshot"),
        null,
        AllIcons.Actions.MenuSaveall,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) {
            // Spread forces the vararg (String[]) constructor; the
            // single-extension overload doesn't exist on the 243 line.
            val descriptor = FileSaverDescriptor(StaleguardBundle.message("timeline.snapshot"), "", *arrayOf("png"))
            val wrapper = FileChooserFactory.getInstance()
                .createSaveFileDialog(descriptor, project)
                .save("staleguard-timeline.png")
                ?: return
            val image = BufferedImage(chart.width.coerceAtLeast(1), chart.height.coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.color = chart.background
            g.fillRect(0, 0, image.width, image.height)
            chart.paint(g)
            g.dispose()
            ImageIO.write(image, "png", wrapper.file)
        }
    }

    private inner class ChartComponent : JComponent() {

        private var entries: List<TimelineEntry> = emptyList()
        private var now: Long = System.currentTimeMillis()

        init {
            toolTipText = "" // enables per-point tooltips via getToolTipText(e)
        }

        fun update(newEntries: List<TimelineEntry>, nowMillis: Long) {
            entries = newEntries
            now = nowMillis
            preferredSize = JBUI.size(600, AXIS_HEIGHT + entries.size * ROW_HEIGHT + 8)
            revalidate()
            repaint()
        }

        override fun getToolTipText(event: MouseEvent): String? {
            val index = (event.y - AXIS_HEIGHT) / ROW_HEIGHT
            val entry = entries.getOrNull(index) ?: return null
            val released = entry.releasedAtMillis
                ?.let { SimpleDateFormat("yyyy-MM-dd").format(Date(it)) }
                ?: StaleguardBundle.message("timeline.tooltip.unknown")
            return StaleguardBundle.message(
                "timeline.tooltip",
                entry.label,
                released,
                entry.upgradeHint ?: StaleguardBundle.message("timeline.tooltip.uptodate"),
            )
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val windowStart = now - TimeUnit.DAYS.toMillis(5 * 365)
            val chartLeft = LABEL_WIDTH
            val chartWidth = (width - chartLeft - RIGHT_PAD).coerceAtLeast(50)

            fun xFor(time: Long): Int {
                val clamped = time.coerceIn(windowStart, now)
                return chartLeft + ((clamped - windowStart).toDouble() / (now - windowStart) * chartWidth).toInt()
            }

            // year ticks
            g2.color = JBColor.border()
            g2.font = JBUI.Fonts.smallFont()
            for (yearBack in 0..5) {
                val t = now - TimeUnit.DAYS.toMillis(365L * yearBack)
                val x = xFor(t)
                g2.drawLine(x, AXIS_HEIGHT - 4, x, AXIS_HEIGHT + entries.size * ROW_HEIGHT)
                val label = SimpleDateFormat("yyyy").format(Date(t))
                g2.drawString(label, x - g2.fontMetrics.stringWidth(label) / 2, AXIS_HEIGHT - 8)
            }

            entries.forEachIndexed { index, entry ->
                val y = AXIS_HEIGHT + index * ROW_HEIGHT
                g2.color = JBColor.foreground()
                val label = if (entry.label.length > 38) "…" + entry.label.takeLast(37) else entry.label
                g2.drawString(label, 6, y + ROW_HEIGHT - 6)

                val barColor = when (entry.bucket) {
                    AgeBucket.FRESH -> FRESH_COLOR
                    AgeBucket.AGING -> AGING_COLOR
                    AgeBucket.STALE -> STALE_COLOR
                    AgeBucket.UNKNOWN -> UNKNOWN_COLOR
                }
                g2.color = barColor
                val startX = entry.releasedAtMillis?.let(::xFor) ?: chartLeft
                g2.fillRoundRect(startX, y + 4, (xFor(now) - startX).coerceAtLeast(4), ROW_HEIGHT - 10, 4, 4)
            }
        }
    }

    private companion object {
        const val ROW_HEIGHT = 20
        const val AXIS_HEIGHT = 28
        const val LABEL_WIDTH = 260
        const val RIGHT_PAD = 16

        val FRESH_COLOR = JBColor(Color(0x59A869), Color(0x499C54))
        val AGING_COLOR = JBColor(Color(0xEDA200), Color(0xF0A732))
        val STALE_COLOR = JBColor(Color(0xDB5860), Color(0xC75450))
        val UNKNOWN_COLOR = JBColor(Color(0x9AA7B0), Color(0x6E7577))
    }
}
