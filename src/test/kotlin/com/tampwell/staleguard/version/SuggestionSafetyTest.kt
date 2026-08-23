package com.tampwell.staleguard.version

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionSafetyTest {

    private fun versions(vararg v: String) = v.map(::MavenVersion)

    private fun steer(
        current: String,
        suggested: String,
        available: List<MavenVersion>,
        vulnerable: Set<String>,
        allowed: (MavenVersion) -> Boolean = { true },
    ) = SuggestionSafety.steerClear(
        MavenVersion(current), MavenVersion(suggested), available,
        includePrereleases = false, allowed = allowed,
        knownVulnerable = { it.value in vulnerable },
    )

    @Test
    fun `clean suggestion passes through untouched`() {
        val steered = steer("2.14.1", "2.17.1", versions("2.14.1", "2.15.0", "2.17.1"), vulnerable = emptySet())
        assertEquals("2.17.1", steered.version.value)
        assertFalse(steered.knownVulnerable)
    }

    @Test
    fun `unknown status counts as acceptable - no steering on cold cache`() {
        val steered = steer("1.0", "2.0", versions("1.0", "2.0"), vulnerable = emptySet())
        assertEquals("2.0", steered.version.value)
    }

    @Test
    fun `vulnerable top suggestion steers down to the nearest clean upgrade`() {
        // The log4j shape: 2.15.0 fixed Log4Shell and carried CVE-2021-45046.
        val steered = steer(
            "2.14.1", "2.15.0",
            versions("2.14.1", "2.14.2", "2.15.0"),
            vulnerable = setOf("2.15.0"),
        )
        assertEquals("2.14.2", steered.version.value)
        assertFalse(steered.knownVulnerable)
    }

    @Test
    fun `steering never goes at or below the current version`() {
        val steered = steer(
            "2.14.1", "2.15.0",
            versions("2.13.0", "2.14.1", "2.15.0"),
            vulnerable = setOf("2.15.0"),
        )
        assertEquals("2.15.0", steered.version.value)
        assertTrue(steered.knownVulnerable)
    }

    @Test
    fun `steering respects the pin filter`() {
        val ceiling = VersionConstraint.parse("2.*")!!
        val steered = steer(
            "2.0", "2.9",
            versions("2.0", "2.5", "2.9", "3.0"),
            vulnerable = setOf("2.9"),
            allowed = ceiling::allows,
        )
        assertEquals("2.5", steered.version.value)
    }

    @Test
    fun `all newer versions vulnerable keeps the top and says so`() {
        val steered = steer(
            "1.0", "1.2",
            versions("1.0", "1.1", "1.2"),
            vulnerable = setOf("1.1", "1.2"),
        )
        assertEquals("1.2", steered.version.value)
        assertTrue(steered.knownVulnerable)
    }

    @Test
    fun `prerelease alternatives stay excluded while steering`() {
        val steered = steer(
            "1.0", "2.0",
            versions("1.0", "1.5-beta1", "2.0"),
            vulnerable = setOf("2.0"),
        )
        assertEquals("2.0", steered.version.value)
        assertTrue(steered.knownVulnerable)
    }
}
