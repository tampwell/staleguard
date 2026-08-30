package com.tampwell.staleguard.impact

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/** Fired whenever a linkage audit finishes and the ambient verdict moved. */
fun interface LinkageVerdictListener {
    fun verdictChanged()

    companion object {
        @JvmField
        val TOPIC: Topic<LinkageVerdictListener> =
            Topic.create("Staleguard linkage verdict", LinkageVerdictListener::class.java)
    }
}

/**
 * The last linkage verdict, kept so the status bar and tool window can show
 * the doctor's answer without anyone opening a dialog. Every audit records
 * here — the explicit check, the watcher's quiet re-checks, the startup
 * baseline — because they all state the same current truth.
 */
@Service(Service.Level.PROJECT)
class LinkageVerdictState(private val project: Project) {

    data class Verdict(val failing: Int, val shadowed: Int, val asOfMillis: Long) {
        val clean: Boolean get() = failing == 0 && shadowed == 0
    }

    @Volatile
    var current: Verdict? = null
        private set

    fun record(report: LinkageAudit.Report) {
        current = verdictOf(report, System.currentTimeMillis())
        project.messageBus.syncPublisher(LinkageVerdictListener.TOPIC).verdictChanged()
    }

    companion object {
        fun getInstance(project: Project): LinkageVerdictState = project.service()

        fun verdictOf(report: LinkageAudit.Report, asOfMillis: Long): Verdict = Verdict(
            failing = report.brokenMembers.size + report.evictedClasses.size,
            shadowed = report.shadowedGroups.size,
            asOfMillis = asOfMillis,
        )
    }
}
