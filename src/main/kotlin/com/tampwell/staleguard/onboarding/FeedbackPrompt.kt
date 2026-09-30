package com.tampwell.staleguard.onboarding

import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.tampwell.staleguard.StaleguardBundle

/**
 * One request for feedback, ever, and only from someone Staleguard has
 * actually done work for: after the third time a checkup found something or
 * a fix was applied. Rating and reporting a problem get equal weight, and the
 * question is asked the same way whatever the outcome was, because asking
 * only people who look happy would be a promotion trick, not a question.
 * Nothing is recorded or sent; it is a local counter and a browser link.
 */
object FeedbackPrompt {

    private const val MOMENTS = "staleguard.feedback.moments"
    private const val ASKED = "staleguard.feedback.asked"
    private const val THRESHOLD = 3

    const val REVIEWS_URL = "https://plugins.jetbrains.com/plugin/33571-staleguard/reviews"
    const val ISSUES_URL = "https://github.com/tampwell/staleguard/issues"

    /** Call after Staleguard did real work: a checkup with findings, or at least one fix applied. */
    fun valueDelivered(project: Project) {
        val properties = PropertiesComponent.getInstance()
        if (properties.getBoolean(ASKED, false)) return
        val moments = properties.getInt(MOMENTS, 0) + 1
        properties.setValue(MOMENTS, moments, 0)
        if (!shouldAsk(moments, asked = false)) return
        properties.setValue(ASKED, true)

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Staleguard")
            .createNotification(
                StaleguardBundle.message("feedback.title"),
                StaleguardBundle.message("feedback.message"),
                NotificationType.INFORMATION,
            )
        notification.addAction(NotificationAction.createSimpleExpiring(StaleguardBundle.message("feedback.rate")) {
            BrowserUtil.browse(REVIEWS_URL)
        })
        notification.addAction(NotificationAction.createSimpleExpiring(StaleguardBundle.message("feedback.issue")) {
            BrowserUtil.browse(ISSUES_URL)
        })
        notification.notify(project)
    }

    internal fun shouldAsk(moments: Int, asked: Boolean): Boolean = !asked && moments == THRESHOLD
}
