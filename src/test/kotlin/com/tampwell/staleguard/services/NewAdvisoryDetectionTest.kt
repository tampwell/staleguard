package com.tampwell.staleguard.services

import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.security.OsvAdvisory
import com.tampwell.staleguard.security.VulnKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewAdvisoryDetectionTest {

    private fun key(artifact: String) = VulnKey(Coordinates("com.example", artifact), "1.0")

    private val advisory = OsvAdvisory("GHSA-xxxx", "CVE-2026-0001", "HIGH", "bad news", "1.1")

    @Test
    fun `clean before with advisories now is news`() {
        val k = key("lib")
        val result = VulnerabilityService.newlyVulnerable(mapOf(k to emptyList())) { listOf(advisory) }
        assertEquals(listOf(k to listOf(advisory)), result)
    }

    @Test
    fun `first-ever lookup of a vulnerable version is discovery, not news`() {
        val result = VulnerabilityService.newlyVulnerable(mapOf(key("lib") to null)) { listOf(advisory) }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `already vulnerable stays quiet`() {
        val result = VulnerabilityService.newlyVulnerable(mapOf(key("lib") to listOf(advisory))) { listOf(advisory) }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `still clean stays quiet`() {
        val result = VulnerabilityService.newlyVulnerable(mapOf(key("lib") to emptyList())) { emptyList() }
        assertTrue(result.isEmpty())
    }
}
