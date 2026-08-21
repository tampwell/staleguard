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
    fun `pins parse coordinate pattern plus constraint`() {
        val toml = """
            [pins]
            dependencies = [
              "org.springframework.boot:*:2.*",
              "com.google.guava:guava:<33",
              "com.example:ranged:[2.0,3.0)",
              "not-three-segments",
              "com.example:broken:^1.0",
            ]
        """.trimIndent()
        val pins = IgnoreRules.parsePins(toml)
        assertEquals(3, pins.size)
        assertTrue(pins[0].appliesTo("org.springframework.boot", "spring-boot-starter-parent"))
        assertFalse(pins[0].appliesTo("org.springframework", "spring-core"))
        assertTrue(pins[0].allows(com.tampwell.staleguard.version.MavenVersion("2.7.18")))
        assertFalse(pins[0].allows(com.tampwell.staleguard.version.MavenVersion("3.2.0")))
        assertFalse(pins[1].allows(com.tampwell.staleguard.version.MavenVersion("33.0.0-jre")))
    }

    @Test
    fun `pins table absent means no pins`() {
        assertTrue(IgnoreRules.parsePins("[ignore]\ndependencies = [\"a:b\"]").isEmpty())
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
