package com.tampwell.staleguard.onboarding

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.tampwell.staleguard.StaleguardBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-time onboarding toast on the first project opened after install.
 * The #1 cause of "plugin doesn't work" reviews is users never opening a
 * build file — so the single actionable button does exactly that.
 * One notification, dismissible, never repeats. No tour, no nagging.
 */
class FirstRunActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        // Every project gets the first-scan watcher; only the first project
        // after install gets the welcome toast.
        FirstScanNotice.getInstance(project).start()

        val properties = PropertiesComponent.getInstance()
        if (properties.getBoolean(FLAG, false)) return
        properties.setValue(FLAG, true)

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Staleguard")
            .createNotification(
                StaleguardBundle.message("onboarding.title"),
                StaleguardBundle.message("onboarding.message"),
                NotificationType.INFORMATION,
            )
        notification.addAction(
            NotificationAction.createSimpleExpiring(StaleguardBundle.message("onboarding.open")) {
                openFirstBuildFile(project)
            },
        )
        withContext(Dispatchers.EDT) {
            if (!project.isDisposed) notification.notify(project)
        }
    }

    private fun openFirstBuildFile(project: Project) {
        DumbService.getInstance(project).runWhenSmart {
            val scope = GlobalSearchScope.projectScope(project)
            val buildFile = BUILD_FILE_NAMES.asSequence()
                .flatMap { FilenameIndex.getVirtualFilesByName(it, scope) }
                .sortedBy { it.path.length } // root build files first
                .firstOrNull()
            if (buildFile != null) {
                FileEditorManager.getInstance(project).openFile(buildFile, true)
            } else {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Staleguard")
                    .createNotification(
                        StaleguardBundle.message("notification.title"),
                        StaleguardBundle.message("onboarding.none"),
                        NotificationType.INFORMATION,
                    )
                    .notify(project)
            }
        }
    }

    private companion object {
        const val FLAG = "staleguard.first.run.shown"
        val BUILD_FILE_NAMES = listOf("pom.xml", "build.gradle", "build.gradle.kts")
    }
}
