package com.tampwell.staleguard.gradle

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Finds every Gradle lockfile in the project and parses it. Discovery rides
 * the file index (one extension query covers the single-file names and the
 * legacy per-configuration directory both), so this must run inside a read
 * action - the tool window's non-blocking snapshot already is one.
 */
object LockfileScan {

    data class Entry(
        val file: VirtualFile,
        val located: Lockfile.Located,
        val lines: List<Lockfile.Line>,
    ) {
        val locked: List<Lockfile.Locked> get() = lines.map { it.locked }
    }

    // Rebuilds fire on every freshness event, and a lockfile can be hundreds
    // of kilobytes - re-parse only when the VFS says the content changed.
    private class Parsed(val stamp: Long, val lines: List<Lockfile.Line>)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Parsed>()

    fun collect(project: Project): List<Entry> {
        // Keys carry the project so two open projects never evict each other.
        val prefix = "${project.locationHash}:"
        val seen = mutableSetOf<String>()
        val entries = FilenameIndex.getAllFilesByExt(project, "lockfile", GlobalSearchScope.projectScope(project))
            .mapNotNull { file ->
                val located = Lockfile.locate(file.path.replace('\\', '/')) ?: return@mapNotNull null
                val key = prefix + file.path
                seen += key
                val cached = cache[key]
                val lines = if (cached != null && cached.stamp == file.modificationStamp) {
                    cached.lines
                } else {
                    val text = runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return@mapNotNull null
                    Lockfile.parseLines(text, located.fallbackConfiguration)
                        .also { cache[key] = Parsed(file.modificationStamp, it) }
                }
                Entry(file, located, lines)
            }
        cache.keys.removeAll { it.startsWith(prefix) && it !in seen }
        return entries
    }

    /**
     * Drift between each drift-eligible lockfile and the declarations of the
     * build file in the same directory. [declaredByDir] maps a module
     * directory (forward slashes) to what its build file declares.
     */
    fun drifts(
        entries: List<Entry>,
        declaredByDir: Map<String, List<Lockfile.Declared>>,
    ): List<Lockfile.Drift> =
        Lockfile.driftAcross(entries.map { it.located to it.locked }, declaredByDir)

    /**
     * Drift straight from planner inputs - the one declaration-to-directory
     * mapping every surface (tool window, status bar) must agree on: a
     * declaration belongs to the directory of the build file that owns it.
     */
    fun driftsFor(
        entries: List<Entry>,
        inputs: List<com.tampwell.staleguard.plan.PlannerInput>,
    ): List<Lockfile.Drift> {
        if (entries.isEmpty()) return emptyList()
        val declaredByDir = inputs.mapNotNull { input ->
            val groupId = input.declared.groupId ?: return@mapNotNull null
            val artifactId = input.declared.artifactId ?: return@mapNotNull null
            val version = input.declared.resolvedVersion ?: return@mapNotNull null
            Triple(input.moduleId.replace('\\', '/').substringBeforeLast('/'), groupId to artifactId, version)
        }.groupBy({ it.first }, { Lockfile.Declared(it.second.first, it.second.second, it.third) })
        return drifts(entries, declaredByDir)
    }
}
