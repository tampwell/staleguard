package com.tampwell.staleguard.version

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpgradeSeverityTest {

    private fun classify(from: String, to: String): UpgradeSeverity? =
        UpgradeSeverity.classify(MavenVersion(from), MavenVersion(to))

    @Test
    fun `patch bump`() {
        assertEquals(UpgradeSeverity.PATCH, classify("1.2.3", "1.2.4"))
    }

    @Test
    fun `minor bump`() {
        assertEquals(UpgradeSeverity.MINOR, classify("1.2.3", "1.3.0"))
    }

    @Test
    fun `major bump`() {
        assertEquals(UpgradeSeverity.MAJOR, classify("1.2.3", "2.0.0"))
    }

    @Test
    fun `single-digit major bump`() {
        assertEquals(UpgradeSeverity.MAJOR, classify("1", "2"))
    }

    @Test
    fun `missing patch component treated as zero`() {
        assertEquals(UpgradeSeverity.PATCH, classify("1.2", "1.2.1"))
    }

    @Test
    fun `missing minor component treated as zero`() {
        assertEquals(UpgradeSeverity.MINOR, classify("1", "1.1"))
    }

    @Test
    fun `qualifier-only change is QUALIFIER`() {
        assertEquals(UpgradeSeverity.QUALIFIER, classify("1.0-rc1", "1.0"))
    }

    @Test
    fun `four-segment micro bump is PATCH`() {
        assertEquals(UpgradeSeverity.PATCH, classify("1.2.3.4", "1.2.3.5"))
    }

    @Test
    fun `not an upgrade returns null`() {
        assertNull(classify("1.2.4", "1.2.3"))
    }

    @Test
    fun `equal versions return null`() {
        assertNull(classify("1.2.3", "1.2.3"))
    }

    @Test
    fun `downgrade across major returns null`() {
        assertNull(classify("2.0", "1.9.9"))
    }

    @Test
    fun `snapshot to release of same number is QUALIFIER`() {
        assertEquals(UpgradeSeverity.QUALIFIER, classify("1.0-SNAPSHOT", "1.0"))
    }

    @Test
    fun `non-numeric versions fall back to QUALIFIER`() {
        assertEquals(UpgradeSeverity.QUALIFIER, classify("alpha", "beta"))
    }

    @Test
    fun `date-based versions compare as MAJOR on year change`() {
        assertEquals(UpgradeSeverity.MAJOR, classify("20230101", "20240101"))
    }
}
