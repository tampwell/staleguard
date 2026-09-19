package com.tampwell.staleguard.impact

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.vfs.VfsUtilCore
import java.nio.file.Files
import java.nio.file.Path

/**
 * The project's own compile output, module by module, in the shape
 * [OwnCodeAudit] judges. The own-code STANDING is still judged on production
 * output only — "your code is clean" is a promise about the code that ships —
 * while test output joins the test-classpath scopes opportunistically when a
 * build has produced it.
 */
object ModuleOutputs {

    fun collect(project: Project): List<OwnCodeAudit.ModuleOutput> = inReadAction {
        ModuleManager.getInstance(project).modules.mapNotNull { module ->
            val recorded = outputPath(module, tests = false) ?: return@mapNotNull null
            val directories = languageDirectories(recorded)
            OwnCodeAudit.ModuleOutput(
                moduleName = module.name,
                scans = scanAll(directories, ownCodeLabel(module.name)),
                newestClassMillis = directories.mapNotNull(JarScanner::newestClassMillis).maxOrNull(),
            )
        }
    }

    /** Compiled TEST classes per module, for the test-classpath scopes. */
    fun collectTestScans(project: Project): Map<String, LinkageAudit.JarScans> = inReadAction {
        ModuleManager.getInstance(project).modules.mapNotNull { module ->
            val recorded = outputPath(module, tests = true) ?: return@mapNotNull null
            val scans = scanAll(languageDirectories(recorded), "your tests (${module.name})")
            scans?.takeIf { it.classes.isNotEmpty() }?.let { module.name to it }
        }.toMap()
    }

    /** Every existing directory each module's compiled classes land in, by module. */
    fun directories(project: Project, tests: Boolean): Map<String, List<Path>> = inReadAction {
        ModuleManager.getInstance(project).modules.mapNotNull { module ->
            val recorded = outputPath(module, tests) ?: return@mapNotNull null
            languageDirectories(recorded).filter { Files.isDirectory(it) }
                .takeIf { it.isNotEmpty() }
                ?.let { module.name to it }
        }.toMap()
    }

    /**
     * The IDE records one output directory per module, but a Gradle build
     * writes each language to its own: build/classes/java/main next to
     * build/classes/kotlin/main. Seeing only the recorded one would hide the
     * rest of a mixed or Kotlin module's code, which for reachability turns
     * into a false "not reached". Every sibling language directory for the
     * same source set joins it; any other layout is taken as recorded.
     */
    internal fun languageDirectories(recorded: Path): List<Path> {
        val sourceSet = recorded.fileName?.toString() ?: return listOf(recorded)
        val classes = recorded.parent?.parent ?: return listOf(recorded)
        if (classes.fileName?.toString() != "classes") return listOf(recorded)
        val siblings = runCatching {
            Files.list(classes).use { stream ->
                stream.map { it.resolve(sourceSet) }.filter { Files.isDirectory(it) }.toList()
            }
        }.getOrDefault(emptyList())
        return (listOf(recorded) + siblings.sorted()).distinct()
    }

    private fun scanAll(directories: List<Path>, label: String): LinkageAudit.JarScans? {
        val scanned = directories.mapNotNull { JarScanner.scanDirectory(it, label) }
        if (scanned.isEmpty()) return null
        return LinkageAudit.JarScans(label, scanned.flatMap { it.classes })
    }

    private fun outputPath(module: com.intellij.openapi.module.Module, tests: Boolean): Path? {
        val extension = CompilerModuleExtension.getInstance(module) ?: return null
        val url = (if (tests) extension.compilerOutputUrlForTests else extension.compilerOutputUrl) ?: return null
        return runCatching { Path.of(VfsUtilCore.urlToPath(url)) }.getOrNull()
    }

    /**
     * The label own-code scans carry into the audit and its reports. Prefixed
     * so a finding from the user's code is visually distinct from a jar
     * finding, which matters: one is fixed by changing a version, the other
     * by changing code or the version it compiles against.
     */
    fun ownCodeLabel(moduleName: String): String = "your code ($moduleName)"
}
