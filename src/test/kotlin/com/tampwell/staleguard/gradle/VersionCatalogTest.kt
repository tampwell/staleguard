package com.tampwell.staleguard.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCatalogTest {

    private val toml = """
        # comment line
        [versions]
        gson = "2.8.9"
        kotlin = "1.9.22"
        slf4j = "1.7.32" # trailing comment

        [libraries]
        gson = { group = "com.google.code.gson", name = "gson", version.ref = "gson" }
        kotlin-stdlib = { group = "org.jetbrains.kotlin", name = "kotlin-stdlib", version.ref = "kotlin" }
        kotlin-reflect = { module = "org.jetbrains.kotlin:kotlin-reflect", version.ref = "kotlin" }
        slf4j-api = { group = "org.slf4j", name = "slf4j-api", version.ref = "slf4j" }
        commons = { group = "commons-collections", name = "commons-collections", version = "3.2.1" }
        shorthand = "org.example:thing:1.0"

        [plugins]
        kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
    """.trimIndent()

    private val parsed = VersionCatalog.parse(toml)

    @Test
    fun `parses versions table`() {
        assertEquals("2.8.9", parsed.versions["gson"])
        assertEquals("1.7.32", parsed.versions["slf4j"])
        assertEquals(3, parsed.versions.size)
    }

    @Test
    fun `resolves group-name library with version ref`() {
        val r = parsed.resolve("gson")!!
        assertEquals("com.google.code.gson", r.group)
        assertEquals("2.8.9", r.version)
        assertEquals("gson", r.versionKey)
    }

    @Test
    fun `resolves dotted accessor against dashed key`() {
        val r = parsed.resolve("kotlin.stdlib")!!
        assertEquals("org.jetbrains.kotlin", r.group)
        assertEquals("kotlin-stdlib", r.name)
        assertEquals("1.9.22", r.version)
    }

    @Test
    fun `resolves module-form library`() {
        val r = parsed.resolve("kotlin.reflect")!!
        assertEquals("kotlin-reflect", r.name)
        assertEquals("1.9.22", r.version)
    }

    @Test
    fun `inline version has null versionKey`() {
        val r = parsed.resolve("commons")!!
        assertEquals("3.2.1", r.version)
        assertNull(r.versionKey)
    }

    @Test
    fun `shorthand module string resolves`() {
        val r = parsed.resolve("shorthand")!!
        assertEquals("org.example", r.group)
        assertEquals("1.0", r.version)
        assertNull(r.versionKey)
    }

    @Test
    fun `unknown accessor resolves to null`() {
        assertNull(parsed.resolve("nope"))
    }

    @Test
    fun `reference count reflects shared versions`() {
        // 2 libraries + the kotlin-jvm plugin share the "kotlin" key
        assertEquals(3, parsed.referenceCount("kotlin"))
        assertEquals(1, parsed.referenceCount("gson"))
        assertEquals(0, parsed.referenceCount("unused"))
    }

    @Test
    fun `plugins table is ignored - same-named keys do not collide`() {
        assertNull(parsed.resolve("kotlin.jvm"))
    }

    @Test
    fun `malformed toml degrades to skipped entries not crashes`() {
        val bad = VersionCatalog.parse("[libraries]\nbroken = { group = }\nok = \"g:a:1\"\n[versions\ngarbage")
        assertEquals(1, bad.libraries.size)
        assertEquals("1", bad.resolve("ok")?.version)
    }

    @Test
    fun `versionValueRange targets the versions table only`() {
        val range = VersionCatalog.versionValueRange(toml, "gson")!!
        assertEquals("2.8.9", toml.substring(range.first, range.last + 1))
        // "kotlin" appears as a key in [plugins] too; range must be the [versions] one
        val kotlinRange = VersionCatalog.versionValueRange(toml, "kotlin")!!
        assertEquals("1.9.22", toml.substring(kotlinRange.first, kotlinRange.last + 1))
    }

    @Test
    fun `versionValueRange null for unknown key`() {
        assertNull(VersionCatalog.versionValueRange(toml, "nope"))
    }

    @Test
    fun `empty text parses to empty catalog`() {
        assertTrue(VersionCatalog.parse("").isEmpty)
    }

    @Test
    fun `rich version resolves through prefer`() {
        val parsed = VersionCatalog.parse(
            """
            [versions]
            lang3 = { strictly = "[3.8, 4.0[", prefer = "3.9" }
            [libraries]
            lang3 = { module = "org.apache.commons:commons-lang3", version.ref = "lang3" }
            """.trimIndent(),
        )
        assertEquals("3.9", parsed.resolve("lang3")?.version)
    }

    @Test
    fun `rich version falls back to require then strictly`() {
        val parsed = VersionCatalog.parse(
            """
            [versions]
            a = { require = "1.4" }
            b = { strictly = "2.0" }
            """.trimIndent(),
        )
        assertEquals("1.4", parsed.versions["a"])
        assertEquals("2.0", parsed.versions["b"])
    }

    @Test
    fun `range-only rich version resolves to nothing`() {
        val parsed = VersionCatalog.parse(
            """
            [versions]
            ranged = { strictly = "[1.0, 2.0[" }
            """.trimIndent(),
        )
        assertNull(parsed.versions["ranged"])
    }

    @Test
    fun `plugins table parses inline and shorthand forms`() {
        val kotlinJvm = parsed.resolvePlugin("kotlin.jvm")!!.second
        assertEquals("org.jetbrains.kotlin.jvm", kotlinJvm.id)
        assertEquals("1.9.22", parsed.pluginVersion(kotlinJvm))
        assertEquals(
            "org.jetbrains.kotlin.jvm" to "org.jetbrains.kotlin.jvm.gradle.plugin",
            kotlinJvm.markerCoordinates,
        )

        val shorthand = VersionCatalog.parse(
            """
            [plugins]
            versions-check = "com.github.ben-manes.versions:0.45.0"
            """.trimIndent(),
        ).plugins["versions-check"]!!
        assertEquals("com.github.ben-manes.versions", shorthand.id)
        assertEquals("0.45.0", shorthand.versionLiteral)
    }

    @Test
    fun `library with nested rich version resolves through the same pick order`() {
        val catalog = VersionCatalog.parse(
            """
            [libraries]
            strict = { module = "com.example:strict", version = { strictly = "2.10" } }
            preferred = { module = "com.example:pref", version = { require = "1.0", prefer = "1.2" } }
            ranged = { module = "com.example:ranged", version = { strictly = "[1.0, 2.0)" } }
            """.trimIndent(),
        )
        assertEquals("2.10", catalog.libraries["strict"]?.versionLiteral)
        assertEquals("1.2", catalog.libraries["preferred"]?.versionLiteral)
        assertNull(catalog.libraries["ranged"]?.versionLiteral)
    }

    @Test
    fun `plugin version refs count toward blast radius`() {
        // fixture: 2 libraries + 1 plugin share the "kotlin" version key
        assertEquals(3, parsed.referenceCount("kotlin"))
    }
}
