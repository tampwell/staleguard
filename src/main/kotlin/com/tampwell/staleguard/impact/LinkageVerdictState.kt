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

    /** One artifact whose RESOLVED version breaks other jars' calls. */
    data class JarProblem(
        val brokenCalls: Int,
        /** A few of the jars whose calls fail, for the message. */
        val callers: List<String>,
        /** THE FIX, when the audit computed one; null on watcher runs. */
        val fixVersion: String?,
    )

    @Volatile
    var current: Verdict? = null
        private set

    /** Broken jars by coordinates, so the build-file inspections can warn at the declaration. */
    @Volatile
    var problems: Map<com.tampwell.staleguard.repository.Coordinates, JarProblem> = emptyMap()
        private set

    fun record(
        report: LinkageAudit.Report,
        identify: (jarName: String) -> com.tampwell.staleguard.repository.Coordinates? = { null },
        fixFor: (jarName: String) -> String? = { null },
    ) {
        current = verdictOf(report, System.currentTimeMillis())
        problems = problemsOf(report, identify, fixFor)
        project.messageBus.syncPublisher(LinkageVerdictListener.TOPIC).verdictChanged()
    }

    companion object {
        fun getInstance(project: Project): LinkageVerdictState = project.service()

        fun verdictOf(report: LinkageAudit.Report, asOfMillis: Long): Verdict = Verdict(
            failing = report.brokenMembers.size + report.evictedClasses.size,
            shadowed = report.shadowedGroups.size,
            asOfMillis = asOfMillis,
        )

        /**
         * Only broken members map to a declared artifact: an evicted class's
         * owning jar is by definition absent, so pinning its blame on a
         * declaration would be a guess.
         */
        fun problemsOf(
            report: LinkageAudit.Report,
            identify: (jarName: String) -> com.tampwell.staleguard.repository.Coordinates?,
            fixFor: (jarName: String) -> String?,
        ): Map<com.tampwell.staleguard.repository.Coordinates, JarProblem> =
            report.brokenMembers.filter { it.ownerJar != null }
                .groupBy { it.ownerJar!! }
                .entries
                .mapNotNull { (jarName, broken) ->
                    identify(jarName)?.let { coordinates ->
                        coordinates to JarProblem(
                            brokenCalls = broken.size,
                            callers = broken.map { it.fromJar }.distinct().sorted().take(CALLERS_SHOWN),
                            fixVersion = fixFor(jarName),
                        )
                    }
                }
                .toMap()

        private const val CALLERS_SHOWN = 3
    }
}
