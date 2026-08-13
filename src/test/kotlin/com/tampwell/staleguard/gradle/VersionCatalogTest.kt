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
        assertEquals(2, parsed.referenceCount("kotlin"))
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
}
