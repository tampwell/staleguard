package com.tampwell.staleguard.reach

import com.tampwell.staleguard.impact.MemberRef
import com.tampwell.staleguard.repository.Coordinates
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Fix diffs on disk. A released version's bytes never change, so a diff
 * between two releases is computed once, ever, and every later reachability
 * run is local. Plain text on purpose: one touched method per line, readable
 * when someone wants to see what a fix actually changed.
 */
class FixDiffCache(private val root: Path) {

    private fun file(coordinates: Coordinates, vulnerable: String, fixed: String): Path =
        root.resolve(safe(coordinates.groupId))
            .resolve(safe(coordinates.artifactId))
            .resolve("${safe(vulnerable)}__${safe(fixed)}.txt")

    fun read(coordinates: Coordinates, vulnerable: String, fixed: String): FixDiff.Result? {
        val lines = runCatching { Files.readAllLines(file(coordinates, vulnerable, fixed)) }.getOrNull() ?: return null
        if (lines.size < 2 || lines[0] != HEADER) return null
        val stats = lines[1].split(' ').associate { it.substringBefore('=') to it.substringAfter('=').toIntOrNull() }
        val touched = lines.drop(2).mapNotNull { line ->
            val parts = line.split(' ')
            if (parts.size == 3) MemberRef(parts[0], parts[1], parts[2]) else null
        }.toSet()
        return FixDiff.Result(
            touched = touched,
            methodsCompared = stats["compared"] ?: return null,
            classesRemoved = stats["removed"] ?: 0,
            classesChanged = stats["changed"] ?: 0,
            unreadable = emptyList(),
        )
    }

    /** Atomic: a crash mid-write leaves the previous state, never a truncated diff read as "fewer changes". */
    fun write(coordinates: Coordinates, vulnerable: String, fixed: String, result: FixDiff.Result) {
        // A diff with unreadable classes is incomplete; recomputing it is the honest cost.
        if (result.unreadable.isNotEmpty()) return
        val target = file(coordinates, vulnerable, fixed)
        runCatching {
            Files.createDirectories(target.parent)
            val temporary = Files.createTempFile(target.parent, "fixdiff", ".tmp")
            val text = buildString {
                appendLine(HEADER)
                appendLine("compared=${result.methodsCompared} removed=${result.classesRemoved} changed=${result.classesChanged}")
                for (method in result.touched) appendLine("${method.owner} ${method.name} ${method.descriptor}")
            }
            Files.writeString(temporary, text)
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private companion object {
        const val HEADER = "staleguard-fixdiff 1"

        fun safe(segment: String): String = segment.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
