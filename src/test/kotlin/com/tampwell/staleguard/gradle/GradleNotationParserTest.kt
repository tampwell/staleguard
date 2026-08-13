package com.tampwell.staleguard.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GradleNotationParserTest {

    @Test
    fun `plain notation parses`() {
        val p = GradleNotationParser.parse("com.google.guava:guava:31.0.1-jre")!!
        assertEquals("com.google.guava", p.group)
        assertEquals("guava", p.name)
        assertEquals("31.0.1-jre", p.version)
        assertEquals("com.google.guava:guava:".length, p.versionStartIndex)
    }

    @Test
    fun `classifier is preserved and version index exact`() {
        val notation = "org.lwjgl:lwjgl:3.3.3:natives-windows"
        val p = GradleNotationParser.parse(notation)!!
        assertEquals("3.3.3", p.version)
        assertEquals(GradleNotationParser.withVersion(notation, p, "3.3.4"), "org.lwjgl:lwjgl:3.3.4:natives-windows")
    }

    @Test
    fun `artifact-only extension notation parses`() {
        val p = GradleNotationParser.parse("org.example:thing:1.2@zip")!!
        assertEquals("1.2", p.version)
        assertEquals("org.example:thing:2.0@zip", GradleNotationParser.withVersion("org.example:thing:1.2@zip", p, "2.0"))
    }

    @Test
    fun `versionless notation returns null`() {
        assertNull(GradleNotationParser.parse("com.google.guava:guava"))
    }

    @Test
    fun `interpolated notation returns null`() {
        assertNull(GradleNotationParser.parse("com.google.guava:guava:\${guavaVersion}"))
    }

    @Test
    fun `empty and malformed notations return null`() {
        assertNull(GradleNotationParser.parse(""))
        assertNull(GradleNotationParser.parse("justastring"))
        assertNull(GradleNotationParser.parse(":missing:1.0"))
        assertNull(GradleNotationParser.parse("g::1.0"))
        assertNull(GradleNotationParser.parse("g:a:"))
    }

    @Test
    fun `withVersion swaps only the version`() {
        val notation = "org.slf4j:slf4j-api:1.7.32"
        val p = GradleNotationParser.parse(notation)!!
        assertEquals("org.slf4j:slf4j-api:2.0.17", GradleNotationParser.withVersion(notation, p, "2.0.17"))
    }
}
