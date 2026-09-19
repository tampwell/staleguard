package com.tampwell.staleguard.reach

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.tampwell.staleguard.repository.Coordinates

/** Fired when a reachability check has recorded new verdicts. */
fun interface ReachabilityListener {
    fun verdictsChanged()

    companion object {
        @JvmField
        val TOPIC: Topic<ReachabilityListener> = Topic.create("Staleguard reachability", ReachabilityListener::class.java)
    }
}

/**
 * The last reachability verdicts, keyed version-exactly: an advisory on
 * jackson-databind 2.13.0 says nothing about 2.13.4, so a bumped dependency
 * stops matching its old verdict the moment the build resolves the new
 * version, with no re-check needed to stop showing a stale claim. Persisted
 * in the workspace file, since a check can involve downloads and should not
 * be lost to a restart.
 */
@Service(Service.Level.PROJECT)
@State(name = "StaleguardReachability", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ReachabilityState : PersistentStateComponent<ReachabilityState.Bean> {

    data class Key(val groupId: String, val artifactId: String, val version: String, val advisoryId: String)

    @Volatile
    var verdicts: Map<Key, ReachVerdict> = emptyMap()
        private set

    @Volatile
    var asOfMillis: Long = 0
        private set

    fun verdictFor(coordinates: Coordinates, version: String, advisoryId: String): ReachVerdict? =
        verdicts[Key(coordinates.groupId, coordinates.artifactId, version, advisoryId)]

    /** Replaces everything: a check states the whole current truth, not a delta. */
    fun record(newVerdicts: Map<Key, ReachVerdict>, nowMillis: Long = System.currentTimeMillis()) {
        verdicts = newVerdicts.toMap()
        asOfMillis = nowMillis
    }

    class Bean {
        var asOfMillis: Long = 0
        var entries: MutableList<EntryBean> = mutableListOf()
    }

    class EntryBean {
        var groupId: String = ""
        var artifactId: String = ""
        var version: String = ""
        var advisoryId: String = ""
        var kind: String = ""
        var inProduction: Boolean = true
        var path: MutableList<String> = mutableListOf()
        var touchedReached: Int = 0
        var touchedTotal: Int = 0
        var reason: String = ""
    }

    override fun getState(): Bean {
        val bean = Bean()
        bean.asOfMillis = asOfMillis
        for ((key, verdict) in verdicts) {
            bean.entries += EntryBean().apply {
                groupId = key.groupId
                artifactId = key.artifactId
                version = key.version
                advisoryId = key.advisoryId
                when (verdict) {
                    is ReachVerdict.Reached -> {
                        kind = REACHED
                        inProduction = verdict.inProduction
                        path.addAll(verdict.path)
                        touchedReached = verdict.touchedReached
                        touchedTotal = verdict.touchedTotal
                    }
                    is ReachVerdict.NotReached -> {
                        kind = NOT_REACHED
                        touchedTotal = verdict.touchedTotal
                    }
                    is ReachVerdict.Unknown -> {
                        kind = UNKNOWN
                        reason = verdict.reason.name
                    }
                }
            }
        }
        return bean
    }

    /** Replaces in full, like every Staleguard state: a loaded file is the whole truth or nothing. */
    override fun loadState(state: Bean) {
        asOfMillis = state.asOfMillis
        verdicts = state.entries.mapNotNull { entry ->
            val verdict = when (entry.kind) {
                REACHED -> ReachVerdict.Reached(entry.inProduction, entry.path.toList(), entry.touchedReached, entry.touchedTotal)
                NOT_REACHED -> ReachVerdict.NotReached(entry.touchedTotal)
                UNKNOWN -> ReachVerdict.Reason.entries.firstOrNull { it.name == entry.reason }?.let { ReachVerdict.Unknown(it) }
                else -> null
            } ?: return@mapNotNull null
            Key(entry.groupId, entry.artifactId, entry.version, entry.advisoryId) to verdict
        }.toMap()
    }

    companion object {
        private const val REACHED = "reached"
        private const val NOT_REACHED = "not-reached"
        private const val UNKNOWN = "unknown"

        fun getInstance(project: Project): ReachabilityState = project.service()
    }
}
