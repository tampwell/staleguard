package com.tampwell.staleguard.gradle

/**
 * What a relock actually did: version movements, new artifacts, artifacts
 * that left. Pure - two parsed lock states in, a structured delta out.
 * Coordinates are deduped by group:artifact (a lockfile lists the same
 * artifact once per configuration set; the first occurrence speaks).
 */
object LockfileDiff {

    data class Movement(val group: String, val name: String, val from: String, val to: String)

    data class Delta(
        val changed: List<Movement>,
        val added: List<Lockfile.Locked>,
        val removed: List<Lockfile.Locked>,
    ) {
        val isEmpty: Boolean get() = changed.isEmpty() && added.isEmpty() && removed.isEmpty()
    }

    fun diff(before: List<Lockfile.Locked>, after: List<Lockfile.Locked>): Delta {
        val beforeByCoordinate = before.associateByFirst()
        val afterByCoordinate = after.associateByFirst()

        val changed = mutableListOf<Movement>()
        val added = mutableListOf<Lockfile.Locked>()
        for ((coordinate, lock) in afterByCoordinate) {
            val previous = beforeByCoordinate[coordinate]
            when {
                previous == null -> added += lock
                previous.version != lock.version ->
                    changed += Movement(lock.group, lock.name, previous.version, lock.version)
            }
        }
        val removed = beforeByCoordinate.filterKeys { it !in afterByCoordinate }.values.toList()
        return Delta(changed, added, removed)
    }

    private fun List<Lockfile.Locked>.associateByFirst(): Map<Pair<String, String>, Lockfile.Locked> {
        val result = linkedMapOf<Pair<String, String>, Lockfile.Locked>()
        for (lock in this) result.putIfAbsent(lock.group to lock.name, lock)
        return result
    }
}
