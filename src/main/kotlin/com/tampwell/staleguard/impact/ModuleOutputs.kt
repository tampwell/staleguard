package com.tampwell.staleguard.impact

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.vfs.VfsUtilCore
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
            val path = outputPath(module, tests = false) ?: return@mapNotNull null
            OwnCodeAudit.ModuleOutput(
                moduleName = module.name,
                scans = JarScanner.scanDirectory(path, ownCodeLabel(module.name)),
                newestClassMillis = JarScanner.newestClassMillis(path),
            )
        }
    }

    /** Compiled TEST classes per module, for the test-classpath scopes. */
    fun collectTestScans(project: Project): Map<String, LinkageAudit.JarScans> = inReadAction {
        ModuleManager.getInstance(project).modules.mapNotNull { module ->
            val path = outputPath(module, tests = true) ?: return@mapNotNull null
            val scans = JarScanner.scanDirectory(path, "your tests (${module.name})")
            scans?.takeIf { it.classes.isNotEmpty() }?.let { module.name to it }
        }.toMap()
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
