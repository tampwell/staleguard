package com.tampwell.staleguard.gradle

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Remembers each lockfile's last-seen state and keeps the delta of the most
 * recent relock, baseline-then-delta like the linkage watcher: the first
 * sighting of a lockfile records silently, and only an actual change becomes
 * a "last relock" story. Persisted in the workspace file so the story
 * survives a restart - a relock is news exactly once, not once per session.
 */
@Service(Service.Level.PROJECT)
@State(name = "StaleguardLockHistory", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class LockHistoryState : PersistentStateComponent<LockHistoryState.Bean> {

    data class Relock(val filePath: String, val delta: LockfileDiff.Delta, val atMillis: Long)

    @Volatile
    var lastRelocks: List<Relock> = emptyList()
        private set

    private val baselines = linkedMapOf<String, List<String>>()

    /**
     * Feed a fresh scan. Diffs each file against its stored baseline,
     * retains one latest relock per file, and advances the baselines.
     * Synchronized because concurrent snapshot rebuilds may race here.
     */
    @Synchronized
    fun update(entries: List<LockfileScan.Entry>, nowMillis: Long = System.currentTimeMillis()) {
        val relocks = lastRelocks.associateBy { it.filePath }.toMutableMap()
        for (entry in entries) {
            val path = entry.file.path.replace('\\', '/')
            val keys = entry.locked.map { "${it.group}:${it.name}:${it.version}" }
            val stored = baselines[path]
            baselines[path] = keys
            if (stored == null || stored == keys) continue
            val delta = LockfileDiff.diff(stored.mapNotNull(::lockedFromKey), entry.locked)
            if (!delta.isEmpty) relocks[path] = Relock(path, delta, nowMillis)
        }
        lastRelocks = relocks.values.sortedByDescending { it.atMillis }.take(MAX_RELOCKS)
    }

    private fun lockedFromKey(key: String): Lockfile.Locked? {
        val parts = key.split(':')
        return if (parts.size == 3) Lockfile.Locked(parts[0], parts[1], parts[2], emptyList()) else null
    }

    // --- persistence ---

    class Bean {
        var baselines: MutableMap<String, MutableList<String>> = mutableMapOf()
        var relocks: MutableList<RelockBean> = mutableListOf()
    }

    class RelockBean {
        var filePath: String = ""
        var atMillis: Long = 0
        var changed: MutableList<String> = mutableListOf() // group:name:from:to
        var added: MutableList<String> = mutableListOf() // group:name:version
        var removed: MutableList<String> = mutableListOf() // group:name:version
    }

    @Synchronized
    override fun getState(): Bean {
        val bean = Bean()
        for ((path, keys) in baselines) bean.baselines[path] = keys.toMutableList()
        for (relock in lastRelocks) {
            bean.relocks += RelockBean().apply {
                filePath = relock.filePath
                atMillis = relock.atMillis
                changed.addAll(relock.delta.changed.map { "${it.group}:${it.name}:${it.from}:${it.to}" })
                added.addAll(relock.delta.added.map { "${it.group}:${it.name}:${it.version}" })
                removed.addAll(relock.delta.removed.map { "${it.group}:${it.name}:${it.version}" })
            }
        }
        return bean
    }

    @Synchronized
    override fun loadState(state: Bean) {
        baselines.clear()
        for ((path, keys) in state.baselines) baselines[path] = keys.toList()
        lastRelocks = state.relocks.mapNotNull { bean ->
            val changed = bean.changed.mapNotNull { entry ->
                val parts = entry.split(':')
                if (parts.size == 4) LockfileDiff.Movement(parts[0], parts[1], parts[2], parts[3]) else null
            }
            val delta = LockfileDiff.Delta(
                changed = changed,
                added = bean.added.mapNotNull(::lockedFromKey),
                removed = bean.removed.mapNotNull(::lockedFromKey),
            )
            if (delta.isEmpty) null else Relock(bean.filePath, delta, bean.atMillis)
        }
    }

    companion object {
        private const val MAX_RELOCKS = 10

        fun getInstance(project: Project): LockHistoryState = project.service()
    }
}
