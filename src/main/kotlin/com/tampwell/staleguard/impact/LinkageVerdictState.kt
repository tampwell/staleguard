package com.tampwell.staleguard.impact

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.tampwell.staleguard.repository.Coordinates

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
 *
 * Persisted in the workspace file so a reopened project shows its last known
 * verdict immediately instead of nothing for the first fifteen seconds. The
 * classpath may have moved while the IDE was closed, but the startup audit
 * corrects that within seconds, and the editor warnings are version-exact so
 * a declaration changed in the meantime never matches a stale problem.
 */
@Service(Service.Level.PROJECT)
@State(name = "StaleguardLinkageVerdict", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class LinkageVerdictState(private val project: Project) : PersistentStateComponent<LinkageVerdictState.Bean> {

    data class Verdict(val failing: Int, val shadowed: Int, val asOfMillis: Long) {
        val clean: Boolean get() = failing == 0 && shadowed == 0
    }

    /** One artifact whose RESOLVED version breaks other jars' calls. */
    data class JarProblem(
        /** The version the audit saw breaking. A bumped declaration stops matching immediately. */
        val version: String,
        val brokenCalls: Int,
        /** A few of the jars whose calls fail, for the message. */
        val callers: List<String>,
        /** THE FIX, when the audit computed one; null on watcher runs. */
        val fixVersion: String?,
        /** The first dependency path that brings this version in; empty when unknown. */
        val provenance: List<String> = emptyList(),
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
        identify: (jarName: String) -> JarCoordinates.Identified? = { null },
        fixFor: (jarName: String) -> String? = { null },
        provenanceFor: (jarName: String) -> List<String> = { emptyList() },
    ) {
        current = verdictOf(report, System.currentTimeMillis())
        problems = problemsOf(report, identify, fixFor, provenanceFor)
        project.messageBus.syncPublisher(LinkageVerdictListener.TOPIC).verdictChanged()
    }

    // Serialization beans: mutable, no-arg, string-keyed — the shape the
    // platform's XML serializer needs, kept out of the domain types above.
    class Bean {
        var hasVerdict: Boolean = false
        var failing: Int = 0
        var shadowed: Int = 0
        var asOfMillis: Long = 0
        var problems: MutableList<ProblemBean> = mutableListOf()
    }

    class ProblemBean {
        var groupId: String = ""
        var artifactId: String = ""
        var version: String = ""
        var brokenCalls: Int = 0
        var callers: String = ""
        var fixVersion: String? = null
        var provenance: MutableList<String> = mutableListOf()
    }

    override fun getState(): Bean = Bean().also { bean ->
        current?.let { verdict ->
            bean.hasVerdict = true
            bean.failing = verdict.failing
            bean.shadowed = verdict.shadowed
            bean.asOfMillis = verdict.asOfMillis
        }
        bean.problems = problems.entries.mapTo(mutableListOf()) { (coordinates, problem) ->
            ProblemBean().also {
                it.groupId = coordinates.groupId
                it.artifactId = coordinates.artifactId
                it.version = problem.version
                it.brokenCalls = problem.brokenCalls
                it.callers = problem.callers.joinToString("|")
                it.fixVersion = problem.fixVersion
                it.provenance = problem.provenance.toMutableList()
            }
        }
    }

    override fun loadState(state: Bean) {
        // The loaded state replaces in full: an empty bean means no verdict,
        // not "keep whatever was here".
        current = if (state.hasVerdict) Verdict(state.failing, state.shadowed, state.asOfMillis) else null
        problems = state.problems.associate { bean ->
            Coordinates(bean.groupId, bean.artifactId) to JarProblem(
                version = bean.version,
                brokenCalls = bean.brokenCalls,
                callers = bean.callers.split('|').filter { it.isNotEmpty() },
                fixVersion = bean.fixVersion,
                provenance = bean.provenance.toList(),
            )
        }
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
            identify: (jarName: String) -> JarCoordinates.Identified?,
            fixFor: (jarName: String) -> String?,
            provenanceFor: (jarName: String) -> List<String> = { emptyList() },
        ): Map<com.tampwell.staleguard.repository.Coordinates, JarProblem> =
            report.brokenMembers.filter { it.ownerJar != null }
                .groupBy { it.ownerJar!! }
                .entries
                .mapNotNull { (jarName, broken) ->
                    identify(jarName)?.let { identified ->
                        identified.coordinates to JarProblem(
                            version = identified.version,
                            brokenCalls = broken.size,
                            callers = broken.map { it.fromJar }.distinct().sorted().take(CALLERS_SHOWN),
                            fixVersion = fixFor(jarName),
                            provenance = provenanceFor(jarName),
                        )
                    }
                }
                .toMap()

        private const val CALLERS_SHOWN = 3
    }
}
