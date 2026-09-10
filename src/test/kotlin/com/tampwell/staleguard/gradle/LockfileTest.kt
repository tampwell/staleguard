package com.tampwell.staleguard.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LockfileTest {

    @Test
    fun `single file format parses coordinates and configurations`() {
        val text = """
            # This is a Gradle generated file for dependency locking.
            # Manual edits can break the build and are not advised.
            com.fasterxml.jackson.core:jackson-databind:2.15.2=compileClasspath,runtimeClasspath
            org.slf4j:slf4j-api:2.0.9=compileClasspath
            empty=annotationProcessor
        """.trimIndent()

        val locked = Lockfile.parse(text)

        assertEquals(2, locked.size)
        assertEquals(
            Lockfile.Locked(
                "com.fasterxml.jackson.core", "jackson-databind", "2.15.2",
                listOf("compileClasspath", "runtimeClasspath"),
            ),
            locked[0],
        )
        assertEquals(listOf("compileClasspath"), locked[1].configurations)
    }

    @Test
    fun `legacy format takes the configuration from the file name fallback`() {
        val locked = Lockfile.parse("org.slf4j:slf4j-api:2.0.9", fallbackConfiguration = "compileClasspath")

        assertEquals(1, locked.size)
        assertEquals(listOf("compileClasspath"), locked[0].configurations)
    }

    @Test
    fun `garbage lines are skipped never fatal`() {
        val text = """
            not a coordinate
            too:few
            a:b:c:d:e=conf
            g:a:1.0=conf
        """.trimIndent()

        val locked = Lockfile.parse(text)

        assertEquals(1, locked.size)
        assertEquals("g", locked[0].group)
    }

    @Test
    fun `drift reports a concrete disagreement once per coordinate`() {
        val locked = listOf(
            Lockfile.Locked("g", "a", "1.0", listOf("compileClasspath")),
            Lockfile.Locked("g", "a", "1.0", listOf("testCompileClasspath")),
            Lockfile.Locked("g", "b", "2.0", listOf("compileClasspath")),
        )
        val declared = listOf(
            Lockfile.Declared("g", "a", "1.1"),
            Lockfile.Declared("g", "b", "2.0"),
        )

        val drifts = Lockfile.drift(locked, declared)

        assertEquals(listOf(Lockfile.Drift("g", "a", "1.0", "1.1")), drifts)
    }

    @Test
    fun `a dynamic declaration never drifts`() {
        val locked = listOf(Lockfile.Locked("g", "a", "1.0", listOf("compileClasspath")))
        for (dynamic in listOf("1.+", "+", "latest.release", "latest.integration", "[1.0,2.0)", "(,1.5]")) {
            val drifts = Lockfile.drift(locked, listOf(Lockfile.Declared("g", "a", dynamic)))
            assertTrue("expected no drift for $dynamic", drifts.isEmpty())
        }
    }

    @Test
    fun `a transitive with no declaration is not drift`() {
        val locked = listOf(Lockfile.Locked("g", "transitive", "3.0", listOf("runtimeClasspath")))

        assertTrue(Lockfile.drift(locked, emptyList()).isEmpty())
    }
}
