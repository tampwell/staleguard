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
    fun `dependabot unconditioned entries are full ignores, conditioned ones are not`() {
        val yaml = """
            version: 2
            updates:
              - package-ecosystem: "maven"
                directory: "/"
                ignore:
                  - dependency-name: "org.springframework:*"
                  - dependency-name: com.example:pinned
                    versions: ["[3.0,)"]
        """.trimIndent()
        assertEquals(listOf("org.springframework:*"), IgnoreRules.parseDependabot(yaml))
    }

    @Test
    fun `dependabot version ranges become pins that block only those versions`() {
        val yaml = """
            ignore:
              - dependency-name: "com.example:lib"
                versions: ["[3.0,)"]
              - dependency-name: "com.example:nomajors"
                update-types: ["version-update:semver-major"]
        """.trimIndent()
        val (pins, extraIgnores) = IgnoreRules.parseDependabotPins(yaml)
        assertTrue(extraIgnores.isEmpty())
        assertEquals(2, pins.size)

        val ranged = pins.single { it.coordinatePattern == "com.example:lib" }
        assertTrue(ranged.allows(v("2.9.9")))
        assertFalse(ranged.allows(v("3.0")))
        assertFalse(ranged.allows(v("4.1")))

        val noMajors = pins.single { it.coordinatePattern == "com.example:nomajors" }
        assertTrue(noMajors.blockMajors)
        assertTrue(noMajors.allows(v("2.0.0"), v("2.9.0")))
        assertFalse(noMajors.allows(v("2.0.0"), v("3.0.0")))
        assertTrue(noMajors.allows(null, v("3.0.0")))
    }

    @Test
    fun `dependabot gem operator versions and multiline arrays parse`() {
        val yaml = """
            ignore:
              - dependency-name: "com.example:ops"
                versions: [
                  ">= 5, < 6",
                  "4.1.7"
                ]
        """.trimIndent()
        val (pins, _) = IgnoreRules.parseDependabotPins(yaml)
        val pin = pins.single()
        assertFalse(pin.allows(v("5.2")))
        assertFalse(pin.allows(v("4.1.7")))
        assertTrue(pin.allows(v("6.0")))
        assertTrue(pin.allows(v("4.1.8")))
    }

    @Test
    fun `unparseable dependabot version condition falls back to a full ignore`() {
        val yaml = """
            ignore:
              - dependency-name: "com.example:weird"
                versions: ["~> 2.0"]
        """.trimIndent()
        val (pins, extraIgnores) = IgnoreRules.parseDependabotPins(yaml)
        assertTrue(pins.isEmpty())
        assertEquals(listOf("com.example:weird"), extraIgnores)
    }

    @Test
    fun `renovate update-type-scoped disable is a pin not a full ignore`() {
        val json = """
            {"packageRules": [
              {"matchPackageNames": ["com.example:lib"], "matchUpdateTypes": ["major"], "enabled": false}
            ]}
        """.trimIndent()
        assertTrue(IgnoreRules.parseRenovate(json).isEmpty())
        val (pins, _) = IgnoreRules.parseRenovatePins(json)
        val pin = pins.single()
        assertTrue(pin.blockMajors)
        assertTrue(pin.allows(v("2.0"), v("2.5")))
        assertFalse(pin.allows(v("2.0"), v("3.0")))
    }

    @Test
    fun `renovate allowedVersions forms - range, npm ops, regex, exact`() {
        val json = """
            {"packageRules": [
              {"matchPackageNames": ["com.a:range"], "allowedVersions": "[2.0,3.0)"},
              {"matchPackageNames": ["com.a:npm"], "allowedVersions": "<3.0.0"},
              {"matchPackageNames": ["com.a:regex"], "allowedVersions": "/^2\\./"},
              {"matchPackageNames": ["com.a:negre"], "allowedVersions": "!/-(alpha|beta)/"},
              {"matchPackageNames": ["com.a:glob**"], "allowedVersions": "2.7.18"}
            ]}
        """.trimIndent()
        val (pins, extraIgnores) = IgnoreRules.parseRenovatePins(json)
        assertTrue(extraIgnores.isEmpty())
        assertEquals(5, pins.size)
        assertTrue(pins[0].allows(v("2.5")))
        assertFalse(pins[0].allows(v("3.0")))
        assertTrue(pins[1].allows(v("2.9.9")))
        assertFalse(pins[1].allows(v("3.0.0")))
        assertTrue(pins[2].allows(v("2.11")))
        assertFalse(pins[2].allows(v("3.0")))
        assertTrue(pins[3].allows(v("2.0")))
        assertFalse(pins[3].allows(v("2.0-beta1")))
        assertTrue(pins[4].appliesTo("com.a", "globanything"))
        assertTrue(pins[4].allows(v("2.7.18")))
        assertFalse(pins[4].allows(v("2.7.19")))
    }

    @Test
    fun `renovate handlebars template cap becomes a full ignore`() {
        val json = """
            {"packageRules": [
              {"matchPackageNames": ["com.a:templated"], "allowedVersions": "<={{add major 1}}"}
            ]}
        """.trimIndent()
        val (pins, extraIgnores) = IgnoreRules.parseRenovatePins(json)
        assertTrue(pins.isEmpty())
        assertEquals(listOf("com.a:templated"), extraIgnores)
    }

    private fun v(value: String) = com.tampwell.staleguard.version.MavenVersion(value)
}
