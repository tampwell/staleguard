package com.tampwell.staleguard.impact

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderEnumerator
import java.nio.file.Path

/**
 * Each module's real classpath views: the production one its code runs with,
 * and the test one its tests run with. They are audited separately because
 * they fail separately — a mixed JUnit platform breaks the test runner while
 * production is fine, and a test-only jar must never pollute a production
 * verdict.
 *
 * Closure semantics follow the build tools': test dependencies are not
 * transitive, so a module's test classpath carries its own test dependencies
 * but only the PRODUCTION side of everything reachable beyond the first hop.
 */
object ModuleScopes {

    data class ModuleScope(
        val moduleName: String,
        val productionJarPaths: List<Path>,
        val testJarPaths: List<Path>,
        /** Modules whose production output is on this module's production classpath. */
        val productionClosure: List<String>,
        /** Modules whose production output is on this module's TEST classpath. */
        val testClosure: List<String>,
    )

    fun collect(project: Project): List<ModuleScope> = inReadAction {
        val byName = ModuleManager.getInstance(project).modules.associateBy { it.name }
        val dependencies: (String, Boolean) -> List<String> = { name, includeTests ->
            byName[name]?.let { module ->
                ModuleRootManager.getInstance(module).getDependencies(includeTests).map { it.name }
            }.orEmpty()
        }
        byName.values.map { module ->
            ModuleScope(
                moduleName = module.name,
                productionJarPaths = OrderEnumerator.orderEntries(module)
                    .recursively().productionOnly().jarPaths(),
                testJarPaths = OrderEnumerator.orderEntries(module).recursively().jarPaths(),
                productionClosure = closure(module.name, firstHopTests = false, dependencies),
                testClosure = closure(module.name, firstHopTests = true, dependencies),
            )
        }
    }

    /** The label a module's test-classpath scope carries in reports. */
    fun testScopeName(moduleName: String): String = "$moduleName (tests)"

    /**
     * BFS where only the first hop may follow test-scope edges — test
     * dependencies are not transitive in any JVM build tool.
     */
    fun closure(
        start: String,
        firstHopTests: Boolean,
        dependencies: (name: String, includeTests: Boolean) -> List<String>,
    ): List<String> {
        val seen = LinkedHashSet<String>()
        seen += start
        val queue = ArrayDeque(dependencies(start, firstHopTests))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current)) continue
            queue += dependencies(current, false)
        }
        return seen.toList()
    }
}
