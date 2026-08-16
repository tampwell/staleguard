package com.tampwell.staleguard.version

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VersionSuggestionTest {

    private fun versions(vararg v: String) = v.map(::MavenVersion)

    @Test
    fun `commons-collections date release is not suggested over semver`() {
        val suggested = VersionSuggestion.suggest(
            current = MavenVersion("3.2.1"),
            available = versions("2.1", "3.2", "3.2.1", "3.2.2", "20031027.000000", "20040616"),
            includePrereleases = false,
        )
        assertEquals("3.2.2", suggested?.value)
    }

    @Test
    fun `date-schema current keeps seeing date-schema suggestions`() {
        val suggested = VersionSuggestion.suggest(
            current = MavenVersion("20031027"),
            available = versions("20031027", "20040616"),
            includePrereleases = false,
        )
        assertEquals("20040616", suggested?.value)
    }

    @Test
    fun `plain semver behavior unchanged`() {
        val suggested = VersionSuggestion.suggest(
            current = MavenVersion("1.7.32"),
            available = versions("1.7.32", "1.7.36", "2.0.18", "2.1.0-alpha1"),
            includePrereleases = false,
        )
        assertEquals("2.0.18", suggested?.value)
    }

    @Test
    fun `prereleases included on request`() {
        val suggested = VersionSuggestion.suggest(
            current = MavenVersion("1.0"),
            available = versions("1.0", "1.1-rc1"),
            includePrereleases = true,
        )
        assertEquals("1.1-rc1", suggested?.value)
    }

    @Test
    fun `prereleases excluded by default`() {
        val suggested = VersionSuggestion.suggest(
            current = MavenVersion("1.0"),
            available = versions("1.0", "1.1-rc1"),
            includePrereleases = false,
        )
        assertEquals("1.0", suggested?.value)
    }

    @Test
    fun `null current applies no date filtering`() {
        val suggested = VersionSuggestion.suggest(
            current = null,
            available = versions("3.2.1", "20040616"),
            includePrereleases = false,
        )
        assertEquals("20040616", suggested?.value)
    }

    @Test
    fun `empty list yields nothing`() {
        assertNull(VersionSuggestion.suggest(MavenVersion("1.0"), emptyList(), false))
    }

    @Test
    fun `five digit versions are not mistaken for dates`() {
        // e.g. build numbers like 10203 are unusual but not 6+ digits
        val suggested = VersionSuggestion.suggest(
            current = MavenVersion("1.2"),
            available = versions("1.2", "10203"),
            includePrereleases = false,
        )
        assertEquals("10203", suggested?.value)
    }
}
