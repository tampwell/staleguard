package com.tampwell.staleguard.version

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionStabilityTest {

    private fun stable(v: String) = MavenVersion(v).isStable

    @Test
    fun `plain releases are stable`() {
        assertTrue(stable("1.2.3"))
        assertTrue(stable("31.0.1-jre"))
        assertTrue(stable("5.0.1.Final"))
        assertTrue(stable("2.0-ga"))
        assertTrue(stable("1.0-sp1"))
    }

    @Test
    fun `prereleases are unstable`() {
        assertFalse(stable("2.0-SNAPSHOT"))
        assertFalse(stable("2.0-rc1"))
        assertFalse(stable("2.0-beta2"))
        assertFalse(stable("2.0-alpha1"))
        assertFalse(stable("2.0-M3"))
        assertFalse(stable("2.0-milestone-1"))
    }

    @Test
    fun `single-letter aliases count as prereleases`() {
        assertFalse(stable("3.0a1")) // alpha-1
        assertFalse(stable("3.0b2")) // beta-2
        assertFalse(stable("3.0M3")) // milestone-3
    }

    @Test
    fun `marker substrings inside other words stay stable`() {
        assertTrue(stable("2.0-arch"))   // contains "rc"
        assertTrue(stable("2.1-search")) // contains "rc"
    }
}
