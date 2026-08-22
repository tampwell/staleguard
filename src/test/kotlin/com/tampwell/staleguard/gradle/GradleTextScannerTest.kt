package com.tampwell.staleguard.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradleTextScannerTest {

    private val catalog = VersionCatalog.parse(
        """
        [versions]
        gson = "2.8.9"

        [libraries]
        gson = { group = "com.google.code.gson", name = "gson", version.ref = "gson" }
        kotlin-stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib", version = "1.9.22" }

        [plugins]
        kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "gson" }
        """.trimIndent(),
    )

    @Test
    fun `kts string notation with parentheses`() {
        val text = """implementation("com.google.guava:guava:31.0.1-jre")"""
        val hits = GradleTextScanner.scan(text, catalog)
        assertEquals(1, hits.size)
        assertEquals("com.google.guava", hits[0].group)
        assertEquals("31.0.1-jre", hits[0].version)
    }

    @Test
    fun `groovy single-quote notation without parentheses`() {
        val hits = GradleTextScanner.scan("implementation 'org.slf4j:slf4j-api:1.7.32'", catalog)
        assertEquals("org.slf4j", hits.single().group)
    }

    @Test
    fun `catalog reference resolves through the catalog`() {
        val hits = GradleTextScanner.scan("implementation(libs.gson)", catalog)
        assertEquals("com.google.code.gson", hits.single().group)
        assertEquals("2.8.9", hits.single().version)
    }

    @Test
    fun `dashed catalog key matches dotted accessor`() {
        val hits = GradleTextScanner.scan("implementation(libs.kotlin.stdlib)", catalog)
        assertEquals("org.jetbrains.kotlin", hits.single().group)
        assertEquals("1.9.22", hits.single().version)
    }

    @Test
    fun `libs versions and plugins accessors are not dependencies`() {
        val text = "val v = libs.versions.gson\nalias(libs.plugins.kotlin.jvm)"
        assertTrue(GradleTextScanner.scan(text, catalog).isEmpty())
    }

    @Test
    fun `interpolated versions never match`() {
        val text = "implementation(\"org.example:thing:\${'$'}{ver}\")"
        assertTrue(GradleTextScanner.scan(text, catalog).isEmpty())
    }

    @Test
    fun `plugin id strings without coordinates never match`() {
        val text = """id("org.jetbrains.kotlin.jvm") version "1.9.22""""
        assertTrue(GradleTextScanner.scan(text, catalog).isEmpty())
    }

    @Test
    fun `unknown catalog accessor is skipped`() {
        assertTrue(GradleTextScanner.scan("implementation(libs.nope)", catalog).isEmpty())
    }

    @Test
    fun `offsets point at the declaration`() {
        val text = "// header\nimplementation(libs.gson)"
        val hit = GradleTextScanner.scan(text, catalog).single()
        assertEquals(text.indexOf("libs.gson"), hit.offset)
    }

    @Test
    fun `multiple declarations all found`() {
        val text = """
            implementation(libs.gson)
            implementation("com.google.guava:guava:31.0.1-jre")
            testImplementation 'junit:junit:4.13.2'
        """.trimIndent()
        assertEquals(3, GradleTextScanner.scan(text, catalog).size)
    }

    @Test
    fun `interpolated versions resolve only when properties are supplied`() {
        val text = """
            implementation("com.example:lib:${'$'}{libVersion}")
            implementation "com.example:groovy:${'$'}gv"
        """.trimIndent()
        assertEquals(0, GradleTextScanner.scan(text, catalog).size)
        val resolved = GradleTextScanner.scan(text, catalog, mapOf("libVersion" to "2.0", "gv" to "1.5"))
        assertEquals(2, resolved.size)
        assertEquals("2.0", resolved.first { it.name == "lib" }.version)
        assertEquals("libVersion", resolved.first { it.name == "lib" }.propertyKey)
        assertEquals("1.5", resolved.first { it.name == "groovy" }.version)
    }

    @Test
    fun `plugin blocks scan only when opted in`() {
        val text = """
            plugins {
                id("com.diffplug.spotless") version "6.25.0"
                id 'org.flywaydb.flyway' version '10.0.0'
                kotlin("jvm") version "1.9.24"
            }
        """.trimIndent()
        assertEquals(0, GradleTextScanner.scan(text, catalog).size)
        val scanned = GradleTextScanner.scan(text, catalog, includePluginBlocks = true)
        assertEquals(3, scanned.size)
        assertEquals("com.diffplug.spotless.gradle.plugin", scanned[0].name)
        assertEquals("10.0.0", scanned.first { it.group == "org.flywaydb.flyway" }.version)
        assertEquals("org.jetbrains.kotlin.jvm.gradle.plugin", scanned.last().name)
        val spotless = scanned[0]
        assertEquals("6.25.0", text.substring(spotless.versionRange!!))
    }
}
