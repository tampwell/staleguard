package com.tampwell.staleguard.impact

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.vfs.VfsUtilCore
import java.nio.file.Path

/**
 * The project's own compile output, module by module, in the shape
 * [OwnCodeAudit] judges. Production output only: test classes call test
 * libraries with their own scopes and would need their own classpath model
 * to audit honestly.
 */
object ModuleOutputs {

    fun collect(project: Project): List<OwnCodeAudit.ModuleOutput> = inReadAction {
        ModuleManager.getInstance(project).modules.mapNotNull { module ->
            val extension = CompilerModuleExtension.getInstance(module) ?: return@mapNotNull null
            val url = extension.compilerOutputUrl ?: return@mapNotNull null
            val path = runCatching { Path.of(VfsUtilCore.urlToPath(url)) }.getOrNull() ?: return@mapNotNull null
            OwnCodeAudit.ModuleOutput(
                moduleName = module.name,
                scans = JarScanner.scanDirectory(path, ownCodeLabel(module.name)),
                newestClassMillis = JarScanner.newestClassMillis(path),
            )
        }
    }

    /**
     * The label own-code scans carry into the audit and its reports. Prefixed
     * so a finding from the user's code is visually distinct from a jar
     * finding, which matters: one is fixed by changing a version, the other
     * by changing code or the version it compiles against.
     */
    fun ownCodeLabel(moduleName: String): String = "your code ($moduleName)"
}
