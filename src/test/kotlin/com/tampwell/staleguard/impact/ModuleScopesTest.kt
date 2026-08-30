package com.tampwell.staleguard.impact

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleScopesTest {

    // prod edges and test edges per module; the lambda answers the way
    // ModuleRootManager.getDependencies(includeTests) does.
    private fun deps(
        prod: Map<String, List<String>>,
        test: Map<String, List<String>> = emptyMap(),
    ): (String, Boolean) -> List<String> = { name, includeTests ->
        prod[name].orEmpty() + if (includeTests) test[name].orEmpty() else emptyList()
    }

    @Test
    fun `production closure follows only production edges`() {
        val closure = ModuleScopes.closure(
            "app",
            firstHopTests = false,
            deps(prod = mapOf("app" to listOf("core"), "core" to listOf("util")), test = mapOf("app" to listOf("testkit"))),
        )

        assertEquals(listOf("app", "core", "util"), closure)
    }

    @Test
    fun `test dependencies are not transitive`() {
        // app tests use testkit; testkit's own tests use fixtures. fixtures
        // is NOT on app's test classpath — no JVM build tool puts it there.
        val closure = ModuleScopes.closure(
            "app",
            firstHopTests = true,
            deps(
                prod = mapOf("testkit" to listOf("commons")),
                test = mapOf("app" to listOf("testkit"), "testkit" to listOf("fixtures")),
            ),
        )

        assertEquals(listOf("app", "testkit", "commons"), closure)
    }

    @Test
    fun `dependency cycles terminate`() {
        val closure = ModuleScopes.closure(
            "a",
            firstHopTests = false,
            deps(prod = mapOf("a" to listOf("b"), "b" to listOf("a"))),
        )

        assertEquals(listOf("a", "b"), closure)
    }
}
