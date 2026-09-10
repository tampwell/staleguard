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

    @Test
    fun `locate classifies the three single file names`() {
        val module = Lockfile.locate("/repo/app/gradle.lockfile")
        assertEquals(Lockfile.Located("/repo/app", null, driftEligible = true), module)

        val settings = Lockfile.locate("/repo/settings-gradle.lockfile")
        assertEquals(Lockfile.Located("/repo", null, driftEligible = false), settings)

        val buildscript = Lockfile.locate("/repo/buildscript-gradle.lockfile")
        assertEquals(Lockfile.Located("/repo", null, driftEligible = false), buildscript)
    }

    @Test
    fun `locate maps a legacy file to its module and configuration`() {
        val located = Lockfile.locate("/repo/app/gradle/dependency-locks/compileClasspath.lockfile")

        assertEquals(Lockfile.Located("/repo/app", "compileClasspath", driftEligible = true), located)
    }

    @Test
    fun `locate rejects a lockfile-suffixed file outside the known shapes`() {
        assertEquals(null, Lockfile.locate("/repo/app/notes/mine.lockfile"))
        assertEquals(null, Lockfile.locate("/repo/app/build.gradle"))
    }

    @Test
    fun `driftAcross matches each lockfile to its own directory's declarations`() {
        val appLock = Lockfile.Located("/repo/app", null, driftEligible = true) to
            listOf(Lockfile.Locked("g", "a", "1.0", listOf("compileClasspath")))
        val libLock = Lockfile.Located("/repo/lib", null, driftEligible = true) to
            listOf(Lockfile.Locked("g", "a", "1.0", listOf("compileClasspath")))
        val settingsLock = Lockfile.Located("/repo", null, driftEligible = false) to
            listOf(Lockfile.Locked("g", "a", "0.9", listOf("classpath")))
        val declaredByDir = mapOf(
            "/repo/app" to listOf(Lockfile.Declared("g", "a", "1.1")),
            "/repo/lib" to listOf(Lockfile.Declared("g", "a", "1.0")),
        )

        val drifts = Lockfile.driftAcross(listOf(appLock, libLock, settingsLock), declaredByDir)

        assertEquals(listOf(Lockfile.Drift("g", "a", "1.0", "1.1")), drifts)
    }

    @Test
    fun `driftAcross collapses identical findings from sibling legacy files`() {
        val compile = Lockfile.Located("/repo/app", "compileClasspath", driftEligible = true) to
            listOf(Lockfile.Locked("g", "a", "1.0", listOf("compileClasspath")))
        val runtime = Lockfile.Located("/repo/app", "runtimeClasspath", driftEligible = true) to
            listOf(Lockfile.Locked("g", "a", "1.0", listOf("runtimeClasspath")))
        val declaredByDir = mapOf("/repo/app" to listOf(Lockfile.Declared("g", "a", "2.0")))

        val drifts = Lockfile.driftAcross(listOf(compile, runtime), declaredByDir)

        assertEquals(1, drifts.size)
    }
}
