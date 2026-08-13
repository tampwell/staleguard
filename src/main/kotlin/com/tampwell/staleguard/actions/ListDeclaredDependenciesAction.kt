package com.tampwell.staleguard.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.maven.PomDependencyCollector
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * Milestone 1 proof: walks every Maven module in the project, collects the
 * declared dependencies, and logs them. Temporary developer-facing surface —
 * the real product UI (inspections + quick fixes) replaces this later.
 */
class ListDeclaredDependenciesAction : AnAction() {

    private val log = logger<ListDeclaredDependenciesAction>()

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val mavenProjects = MavenProjectsManager.getInstance(project).projects

        val notifier = NotificationGroupManager.getInstance().getNotificationGroup("Staleguard")

        if (mavenProjects.isEmpty()) {
            notifier
                .createNotification(
                    StaleguardBundle.message("notification.title"),
                    StaleguardBundle.message("dependencies.none"),
                    NotificationType.WARNING,
                )
                .notify(project)
            return
        }

        var total = 0
        for (mavenProject in mavenProjects) {
            val dependencies = PomDependencyCollector.collect(project, mavenProject.file)
            total += dependencies.size
            log.info("Staleguard: ${mavenProject.displayName} declares ${dependencies.size} dependencies:")
            dependencies.forEach { log.info("Staleguard:   $it") }
        }

        notifier
            .createNotification(
                StaleguardBundle.message("notification.title"),
                StaleguardBundle.message("dependencies.found", total, mavenProjects.size),
                NotificationType.INFORMATION,
            )
            .notify(project)
    }
}
