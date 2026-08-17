package com.tampwell.staleguard.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IgnoreRulesTest {

    @Test
    fun `exact and wildcard patterns match correctly`() {
        assertTrue(IgnoreRules.matches("org.slf4j:slf4j-api", "org.slf4j", "slf4j-api"))
        assertTrue(IgnoreRules.matches("com.internal:*", "com.internal", "anything"))
        assertTrue(IgnoreRules.matches("com.internal*", "com.internal.sub", "thing"))
        assertTrue(IgnoreRules.matches("*:guava", "com.google.guava", "guava"))
        assertFalse(IgnoreRules.matches("org.slf4j:slf4j-api", "org.slf4j", "slf4j-simple"))
        assertFalse(IgnoreRules.matches("com.internal:*", "com.internally", "x"))
    }

    @Test
    fun `dots in patterns are literal`() {
        assertFalse(IgnoreRules.matches("com.internal:*", "comXinternal", "x"))
    }

    @Test
    fun `staleguard toml single and multi line arrays`() {
        val single = """
            [ignore]
            dependencies = ["a:b", "c:*"]
        """.trimIndent()
        assertEquals(listOf("a:b", "c:*"), IgnoreRules.parseStaleguardToml(single))

        val multi = """
            [policy]
            other = "x"
            [ignore]
            dependencies = [
                "com.internal:*",
                "org.slf4j:slf4j-api",
            ]
        """.trimIndent()
        assertEquals(listOf("com.internal:*", "org.slf4j:slf4j-api"), IgnoreRules.parseStaleguardToml(multi))
    }

    @Test
    fun `strings outside the ignore table are not ignores`() {
        val toml = """
            [other]
            dependencies = ["not:me"]
        """.trimIndent()
        assertTrue(IgnoreRules.parseStaleguardToml(toml).isEmpty())
    }

    @Test
    fun `renovate ignoreDeps and disabled packageRules`() {
        val json = """
            {
              "ignoreDeps": ["com.example:locked"],
              "packageRules": [
                {"matchPackageNames": ["org.slow:lib"], "enabled": false},
                {"matchPackagePrefixes": ["com.corp"], "enabled": false},
                {"matchPackageNames": ["org.active:lib"], "enabled": true}
              ]
            }
        """.trimIndent()
        val rules = IgnoreRules.parseRenovate(json)
        assertEquals(listOf("com.example:locked", "org.slow:lib", "com.corp*"), rules)
    }

    @Test
    fun `malformed renovate json is an empty list`() {
        assertTrue(IgnoreRules.parseRenovate("{oops").isEmpty())
    }

    @Test
    fun `dependabot ignore entries are extracted`() {
        val yaml = """
            version: 2
            updates:
              - package-ecosystem: "maven"
                directory: "/"
                ignore:
                  - dependency-name: "org.springframework:*"
                  - dependency-name: com.example:pinned
                    versions: ["5.x"]
        """.trimIndent()
        assertEquals(
            listOf("org.springframework:*", "com.example:pinned"),
            IgnoreRules.parseDependabot(yaml),
        )
    }
}
