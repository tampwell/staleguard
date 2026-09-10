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

    fun collect(project: Project): List<Entry> =
        FilenameIndex.getAllFilesByExt(project, "lockfile", GlobalSearchScope.projectScope(project))
            .mapNotNull { file ->
                val located = Lockfile.locate(file.path.replace('\\', '/')) ?: return@mapNotNull null
                val text = runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return@mapNotNull null
                Entry(file, located, Lockfile.parseLines(text, located.fallbackConfiguration))
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
}
