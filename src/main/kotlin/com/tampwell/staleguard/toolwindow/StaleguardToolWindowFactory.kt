package com.tampwell.staleguard.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class StaleguardToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()

        val stats = StaleguardStatsPanel(project)
        val statsContent = contentFactory.createContent(
            stats, com.tampwell.staleguard.StaleguardBundle.message("toolwindow.tab.stats"), false,
        )
        Disposer.register(statsContent, stats)
        toolWindow.contentManager.addContent(statsContent)

        val timeline = TimelinePanel(project)
        val timelineContent = contentFactory.createContent(
            timeline, com.tampwell.staleguard.StaleguardBundle.message("toolwindow.tab.timeline"), false,
        )
        Disposer.register(timelineContent, timeline)
        toolWindow.contentManager.addContent(timelineContent)
    }
}
