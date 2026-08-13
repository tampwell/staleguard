package com.tampwell.staleguard.inspection

import org.junit.Assert.assertEquals
import org.junit.Test

class FixTargetTest {

    @Test
    fun `literal version targets the version element`() {
        assertEquals(FixTarget.Literal, FixTarget.of("1.2.3"))
    }

    @Test
    fun `qualified literal is still literal`() {
        assertEquals(FixTarget.Literal, FixTarget.of("31.0.1-jre"))
    }

    @Test
    fun `pure property reference targets the property definition`() {
        assertEquals(FixTarget.Property("guava.version"), FixTarget.of("\${guava.version}"))
    }

    @Test
    fun `property reference with surrounding whitespace still resolves`() {
        assertEquals(FixTarget.Property("x"), FixTarget.of(" \${x} "))
    }

    @Test
    fun `managed version - null raw - offers no fix`() {
        assertEquals(FixTarget.None, FixTarget.of(null))
    }

    @Test
    fun `mixed literal and property text offers no fix`() {
        assertEquals(FixTarget.None, FixTarget.of("\${major}.0.1"))
    }

    @Test
    fun `two property references offer no fix`() {
        assertEquals(FixTarget.None, FixTarget.of("\${a}\${b}"))
    }

    @Test
    fun `project builtin references are not editable properties`() {
        assertEquals(FixTarget.None, FixTarget.of("\${project.version}"))
    }

    @Test
    fun `empty version offers no fix`() {
        assertEquals(FixTarget.None, FixTarget.of(""))
    }
}
