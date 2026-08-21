package com.tampwell.staleguard.version

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionConstraintTest {

    private fun allows(constraint: String, version: String): Boolean =
        VersionConstraint.parse(constraint)!!.allows(MavenVersion(version))

    @Test
    fun `prefix wildcard is segment-aware`() {
        assertTrue(allows("2.*", "2.0"))
        assertTrue(allows("2.*", "2.7.18"))
        assertTrue(allows("2.*", "2"))
        assertFalse(allows("2.*", "22.1"))
        assertFalse(allows("2.*", "3.0"))
        assertTrue(allows("2.x", "2.9.9"))
        assertTrue(allows("1.9.*", "1.9.24"))
        assertFalse(allows("1.9.*", "1.10.0"))
    }

    @Test
    fun `comparisons use maven ordering`() {
        assertTrue(allows("<33", "32.1.3-jre"))
        assertFalse(allows("<33", "33.0.0-jre"))
        assertTrue(allows("<=2.9", "2.9"))
        assertFalse(allows("<2.9", "2.9"))
        assertTrue(allows(">=3", "3.0.0"))
        assertFalse(allows("!= 5.1", "5.1"))
        assertTrue(allows("!= 5.1", "5.1.1"))
        // Maven ordering: 2.10 > 2.9 (numeric segments, not lexicographic)
        assertTrue(allows("<2.10", "2.9"))
        assertFalse(allows("<2.10", "2.10"))
    }

    @Test
    fun `comma means AND for operator terms`() {
        assertTrue(allows(">=2, <3", "2.7.18"))
        assertFalse(allows(">=2, <3", "3.0.0"))
        assertFalse(allows(">=2, <3", "1.9"))
    }

    @Test
    fun `maven bracket ranges`() {
        assertTrue(allows("[1.0,2.0)", "1.0"))
        assertTrue(allows("[1.0,2.0)", "1.9.9"))
        assertFalse(allows("[1.0,2.0)", "2.0"))
        assertTrue(allows("(1.0,2.0]", "2.0"))
        assertFalse(allows("(1.0,2.0]", "1.0"))
        assertTrue(allows("(,3.0]", "0.1"))
        assertFalse(allows("(,3.0]", "3.0.1"))
        assertTrue(allows("[1.4,)", "99"))
        assertFalse(allows("[1.4,)", "1.3"))
        assertTrue(allows("[1.5]", "1.5"))
        assertFalse(allows("[1.5]", "1.5.1"))
    }

    @Test
    fun `bracket unions mean OR`() {
        assertTrue(allows("[1,2),[3,4)", "1.5"))
        assertTrue(allows("[1,2),[3,4)", "3.0"))
        assertFalse(allows("[1,2),[3,4)", "2.5"))
    }

    @Test
    fun `bare version is exact`() {
        assertTrue(allows("2.7.18", "2.7.18"))
        assertFalse(allows("2.7.18", "2.7.19"))
    }

    @Test
    fun `garbage does not parse`() {
        assertNull(VersionConstraint.parse(""))
        assertNull(VersionConstraint.parse("*"))
        assertNull(VersionConstraint.parse("[oops"))
        assertNull(VersionConstraint.parse("^1.0.0"))
        assertNull(VersionConstraint.parse("~> 2.0"))
        assertNull(VersionConstraint.parse(">= 2, banana"))
        assertNull(VersionConstraint.parse("(,)"))
        assertNull(VersionConstraint.parse("(1.5)"))
    }
}
