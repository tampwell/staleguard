package com.tampwell.staleguard.maven

import org.junit.Assert.assertEquals
import org.junit.Test

class MavenPropertyInterpolatorTest {

    @Test
    fun `plain version passes through untouched`() {
        assertEquals("1.2.3", MavenPropertyInterpolator.interpolate("1.2.3", emptyMap()))
    }

    @Test
    fun `simple property resolves`() {
        val props = mapOf("spring.version" to "6.2.1")
        assertEquals("6.2.1", MavenPropertyInterpolator.interpolate("\${spring.version}", props))
    }

    @Test
    fun `placeholder embedded in text resolves in place`() {
        val props = mapOf("major" to "2")
        assertEquals("2.0-SNAPSHOT", MavenPropertyInterpolator.interpolate("\${major}.0-SNAPSHOT", props))
    }

    @Test
    fun `multiple placeholders in one value all resolve`() {
        val props = mapOf("major" to "1", "minor" to "4")
        assertEquals("1.4", MavenPropertyInterpolator.interpolate("\${major}.\${minor}", props))
    }

    @Test
    fun `unknown property stays literal like Maven does`() {
        assertEquals("\${nope}", MavenPropertyInterpolator.interpolate("\${nope}", emptyMap()))
    }

    @Test
    fun `transitive property chain resolves`() {
        val props = mapOf("a" to "\${b}", "b" to "\${c}", "c" to "9.9")
        assertEquals("9.9", MavenPropertyInterpolator.interpolate("\${a}", props))
    }

    @Test
    fun `cyclic properties terminate instead of hanging`() {
        val props = mapOf("a" to "\${b}", "b" to "\${a}")
        val result = MavenPropertyInterpolator.interpolate("\${a}", props)
        // Exact literal doesn't matter; termination and a placeholder-shaped result do.
        assert(result.startsWith("\${")) { "unexpected result: $result" }
    }

    @Test
    fun `self-referential property terminates`() {
        val props = mapOf("a" to "\${a}")
        assertEquals("\${a}", MavenPropertyInterpolator.interpolate("\${a}", props))
    }

    @Test
    fun `project builtins resolve when provided`() {
        val props = mapOf("project.version" to "3.1.0")
        assertEquals("3.1.0", MavenPropertyInterpolator.interpolate("\${project.version}", props))
    }

    @Test
    fun `value with no dollar sign is returned without regex work`() {
        assertEquals("release", MavenPropertyInterpolator.interpolate("release", mapOf("release" to "x")))
    }

    @Test
    fun `empty string is safe`() {
        assertEquals("", MavenPropertyInterpolator.interpolate("", emptyMap()))
    }

    @Test
    fun `dollar without braces is untouched`() {
        assertEquals("1.0$", MavenPropertyInterpolator.interpolate("1.0$", emptyMap()))
    }
}
