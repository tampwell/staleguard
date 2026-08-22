package com.tampwell.staleguard.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GradlePropertiesTest {

    private val text = """
        # build tuning
        org.gradle.jvmargs=-Xmx2g
        libVersion=2.10.1
        spring.version = 5.3.31
        colonStyle: 1.4
        ! ignored=nope
        libVersion=2.11.0
    """.trimIndent()

    @Test
    fun `parses entries with last assignment winning`() {
        val values = GradleProperties.parse(text)
        assertEquals("2.11.0", values["libVersion"])
        assertEquals("5.3.31", values["spring.version"])
        assertEquals("1.4", values["colonStyle"])
        assertEquals("-Xmx2g", values["org.gradle.jvmargs"])
        assertNull(values["ignored"])
    }

    @Test
    fun `value range points at the effective (last) assignment`() {
        val range = GradleProperties.valueRange(text, "libVersion")!!
        assertEquals("2.11.0", text.substring(range))
        val replaced = text.replaceRange(range, "3.0.0")
        assertEquals("3.0.0", GradleProperties.parse(replaced)["libVersion"])
    }

    @Test
    fun `crlf files produce correct offsets`() {
        val crlf = "a=1\r\nlibVersion=2.10.1\r\nb=2\r\n"
        val range = GradleProperties.valueRange(crlf, "libVersion")!!
        assertEquals("2.10.1", crlf.substring(range))
    }

    @Test
    fun `unknown key has no range`() {
        assertNull(GradleProperties.valueRange(text, "missing"))
    }
}
