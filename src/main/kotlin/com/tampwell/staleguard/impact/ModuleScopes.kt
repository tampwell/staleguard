package com.tampwell.staleguard.impact

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderEnumerator
import java.nio.file.Path

/**
 * Each module's real classpath view: its recursively resolved library jars
 * plus the modules whose compiled output rides along with it at runtime.
 */
object ModuleScopes {

    data class ModuleScope(
        val moduleName: String,
        val jarPaths: List<Path>,
        /** This module plus its transitive module dependencies, output-wise. */
        val closureModules: List<String>,
    )

    fun collect(project: Project): List<ModuleScope> = inReadAction {
        ModuleManager.getInstance(project).modules.map { module ->
            ModuleScope(
                moduleName = module.name,
                jarPaths = OrderEnumerator.orderEntries(module).recursively().jarPaths(),
                closureModules = closureOf(module),
            )
        }
    }

    private fun closureOf(module: Module): List<String> {
        val seen = LinkedHashSet<Module>()
        val queue = ArrayDeque<Module>()
        queue += module
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current)) continue
            queue += ModuleRootManager.getInstance(current).dependencies
        }
        return seen.map { it.name }
    }
}
