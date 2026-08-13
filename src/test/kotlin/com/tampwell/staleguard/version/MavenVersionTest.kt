/*
 * Ported from Apache Maven's ComparableVersionTest
 * (https://github.com/apache/maven, Apache License 2.0) so that
 * MavenVersion is behavior-locked to the reference implementation,
 * including regression cases MNG-5568, MNG-6572, MNG-6964, MNG-7644,
 * MNG-7700 and MNG-7714.
 */
package com.tampwell.staleguard.version

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MavenVersionTest {

    private fun newComparable(version: String): MavenVersion {
        val ret = MavenVersion(version)
        val canonical = ret.canonical
        val parsedCanonical = MavenVersion(canonical).canonical
        assertEquals(
            "canonical( $version ) = $canonical -> canonical: $parsedCanonical",
            canonical,
            parsedCanonical,
        )
        return ret
    }

    private fun checkVersionsOrder(versions: Array<String>) {
        val c = versions.map { newComparable(it) }
        for (i in 1 until versions.size) {
            val low = c[i - 1]
            for (j in i until versions.size) {
                val high = c[j]
                assertTrue("expected $low < $high", low < high)
                assertTrue("expected $high > $low", high > low)
            }
        }
    }

    private fun checkVersionsOrder(v1: String, v2: String) {
        val c1 = newComparable(v1)
        val c2 = newComparable(v2)
        assertTrue("expected $v1 < $v2", c1 < c2)
        assertTrue("expected $v2 > $v1", c2 > c1)
    }

    private fun checkVersionsEqual(v1: String, v2: String) {
        val c1 = newComparable(v1)
        val c2 = newComparable(v2)
        assertEquals("expected $v1 == $v2", 0, c1.compareTo(c2))
        assertEquals("expected $v2 == $v1", 0, c2.compareTo(c1))
        assertEquals("expected same hashcode for $v1 and $v2", c1.hashCode(), c2.hashCode())
        assertEquals("expected $v1.equals( $v2 )", c1, c2)
        assertEquals("expected $v2.equals( $v1 )", c2, c1)
    }

    private fun checkVersionsHaveSameOrder(v1: String, v2: String) {
        val c1 = MavenVersion(v1)
        val c2 = MavenVersion(v2)
        assertEquals("expected $v1 == $v2", 0, c1.compareTo(c2))
        assertEquals("expected $v2 == $v1", 0, c2.compareTo(c1))
    }

    private fun checkVersionsArrayEqual(array: Array<String>) {
        for (i in array.indices) {
            for (j in i until array.size) {
                checkVersionsEqual(array[i], array[j])
            }
        }
    }

    @Test
    fun versionsQualifier() = checkVersionsOrder(
        arrayOf(
            "1-alpha2snapshot", "1-alpha2", "1-alpha-123", "1-beta-2", "1-beta123", "1-m2", "1-m11",
            "1-rc", "1-cr2", "1-rc123", "1-SNAPSHOT", "1", "1-sp", "1-sp2", "1-sp123", "1-abc",
            "1-def", "1-pom-1", "1-1-snapshot", "1-1", "1-2", "1-123",
        ),
    )

    @Test
    fun versionsNumber() = checkVersionsOrder(
        arrayOf(
            "2.0", "2.0.a", "2-1", "2.0.2", "2.0.123", "2.1.0", "2.1-a", "2.1b", "2.1-c", "2.1-1",
            "2.1.0.1", "2.2", "2.123", "11.a2", "11.a11", "11.b2", "11.b11", "11.m2", "11.m11",
            "11", "11.a", "11b", "11c", "11m",
        ),
    )

    @Test
    fun versionsEqual() {
        newComparable("1.0-alpha")
        checkVersionsEqual("1", "1")
        checkVersionsEqual("1", "1.0")
        checkVersionsEqual("1", "1.0.0")
        checkVersionsEqual("1.0", "1.0.0")
        checkVersionsEqual("1", "1-0")
        checkVersionsEqual("1", "1.0-0")
        checkVersionsEqual("1.0", "1.0-0")
        // no separator between number and character
        checkVersionsEqual("1a", "1-a")
        checkVersionsEqual("1a", "1.0-a")
        checkVersionsEqual("1a", "1.0.0-a")
        checkVersionsEqual("1.0a", "1-a")
        checkVersionsEqual("1.0.0a", "1-a")
        checkVersionsEqual("1x", "1-x")
        checkVersionsEqual("1x", "1.0-x")
        checkVersionsEqual("1x", "1.0.0-x")
        checkVersionsEqual("1.0x", "1-x")
        checkVersionsEqual("1.0.0x", "1-x")
        checkVersionsEqual("1cr", "1rc")

        // special "aliases" a, b and m for alpha, beta and milestone
        checkVersionsEqual("1a1", "1-alpha-1")
        checkVersionsEqual("1b2", "1-beta-2")
        checkVersionsEqual("1m3", "1-milestone-3")

        // case insensitive
        checkVersionsEqual("1X", "1x")
        checkVersionsEqual("1A", "1a")
        checkVersionsEqual("1B", "1b")
        checkVersionsEqual("1M", "1m")
        checkVersionsEqual("1Cr", "1Rc")
        checkVersionsEqual("1cR", "1rC")
        checkVersionsEqual("1m3", "1Milestone3")
        checkVersionsEqual("1m3", "1MileStone3")
        checkVersionsEqual("1m3", "1MILESTONE3")
    }

    @Test
    fun versionsHaveSameOrderButAreNotEqual() {
        checkVersionsHaveSameOrder("1ga", "1")
        checkVersionsHaveSameOrder("1release", "1")
        checkVersionsHaveSameOrder("1final", "1")
        checkVersionsHaveSameOrder("1Ga", "1")
        checkVersionsHaveSameOrder("1GA", "1")
        checkVersionsHaveSameOrder("1RELEASE", "1")
        checkVersionsHaveSameOrder("1RELeaSE", "1")
        checkVersionsHaveSameOrder("1Final", "1")
        checkVersionsHaveSameOrder("1FinaL", "1")
        checkVersionsHaveSameOrder("1FINAL", "1")
    }

    @Test
    fun versionComparing() {
        checkVersionsOrder("1", "2")
        checkVersionsOrder("1.5", "2")
        checkVersionsOrder("1", "2.5")
        checkVersionsOrder("1.0", "1.1")
        checkVersionsOrder("1.1", "1.2")
        checkVersionsOrder("1.0.0", "1.1")
        checkVersionsOrder("1.0.1", "1.1")
        checkVersionsOrder("1.1", "1.2.0")

        checkVersionsOrder("1.0-alpha-1", "1.0")
        checkVersionsOrder("1.0-alpha-1", "1.0-alpha-2")
        checkVersionsOrder("1.0-alpha-1", "1.0-beta-1")

        checkVersionsOrder("1.0-beta-1", "1.0-SNAPSHOT")
        checkVersionsOrder("1.0-SNAPSHOT", "1.0")
        checkVersionsOrder("1.0-alpha-1-SNAPSHOT", "1.0-alpha-1")

        checkVersionsOrder("1.0", "1.0-1")
        checkVersionsOrder("1.0-1", "1.0-2")
        checkVersionsOrder("1.0.0", "1.0-1")

        checkVersionsOrder("2.0-1", "2.0.1")
        checkVersionsOrder("2.0.1-klm", "2.0.1-lmn")
        checkVersionsOrder("2.0.1", "2.0.1-xyz")

        checkVersionsOrder("2.0.1", "2.0.1-123")
        checkVersionsOrder("2.0.1-xyz", "2.0.1-123")
    }

    @Test
    fun leadingZeroes() {
        checkVersionsOrder("0.7", "2")
        checkVersionsOrder("0.2", "1.0.7")
    }

    @Test
    fun digitGreaterThanNonAscii() {
        val c1 = MavenVersion("1")
        val c2 = MavenVersion("é")
        assertTrue("expected 1 > é", c1 > c2)
        assertTrue("expected é < 1", c2 < c1)
    }

    @Test
    fun digitGreaterThanNonBmpCharacters() {
        val c1 = MavenVersion("1")
        // MATHEMATICAL SANS-SERIF DIGIT TWO
        val c2 = MavenVersion("𝟤")
        assertTrue("expected 1 > 𝟤", c1 > c2)
        assertTrue("expected 𝟤 < 1", c2 < c1)
    }

    @Test
    fun getCanonical() {
        // MNG-7700
        newComparable("0.x")
        newComparable("0-x")
        newComparable("0.rc")
        newComparable("0-1")

        assertEquals("x", MavenVersion("0.x").canonical)
        assertEquals("0.2", MavenVersion("0.2").canonical)
    }

    @Test
    fun lexicographicAsciiSortOrder() {
        // Case-insensitive — an explicit, documented deviation from Semver 1.0.
        val lower = MavenVersion("1.0.0-alpha1")
        val upper = MavenVersion("1.0.0-ALPHA1")
        assertEquals("expected 1.0.0-ALPHA1 == 1.0.0-alpha1", 0, upper.compareTo(lower))
        assertEquals("expected 1.0.0-alpha1 == 1.0.0-ALPHA1", 0, lower.compareTo(upper))
    }

    @Test
    fun compareLowerCaseToUpperCaseAscii() {
        checkVersionsHaveSameOrder("1.a", "1.A")
    }

    @Test
    fun compareLowerCaseToUpperCaseNonAscii() {
        checkVersionsHaveSameOrder("1.é", "1.É")
    }

    @Test
    fun compareDigitToLetter() {
        val seven = MavenVersion("7")
        val capitalJ = MavenVersion("J")
        val lowerCaseC = MavenVersion("c")
        assertTrue("expected 7 > J", seven > capitalJ)
        assertTrue("expected J < 7", capitalJ < seven)
        assertTrue("expected 7 > c", seven > lowerCaseC)
        assertTrue("expected c < 7", lowerCaseC < seven)
    }

    @Test
    fun nonAsciiDigits() { // These should not be treated as digits.
        val asciiOne = MavenVersion("1")
        val arabicEight = MavenVersion("٨")
        val asciiNine = MavenVersion("9")
        assertTrue("expected 1 > ٨", asciiOne > arabicEight)
        assertTrue("expected ٨ < 1", arabicEight < asciiOne)
        assertTrue("expected 9 > ٨", asciiNine > arabicEight)
        assertTrue("expected ٨ < 9", arabicEight < asciiNine)
    }

    @Test
    fun lexicographicOrder() {
        val aardvark = MavenVersion("aardvark")
        val zebra = MavenVersion("zebra")
        assertTrue(zebra > aardvark)
        assertTrue(aardvark < zebra)

        // Greek zebra
        val greek = MavenVersion("ζέβρα")
        assertTrue(greek > zebra)
        assertTrue(zebra < greek)
    }

    /** MNG-5568: transitivity with unusual versions like 6.1H.5-beta. */
    @Test
    fun mng5568() {
        val a = "6.1.0"
        val b = "6.1.0rc3"
        val c = "6.1H.5-beta"

        checkVersionsOrder(b, a) // classical
        checkVersionsOrder(b, c) // now b < c, but before MNG-5568, we had b > c
        checkVersionsOrder(a, c)
    }

    /** MNG-6572: very large numeric components. */
    @Test
    fun mng6572() {
        val a = "20190126.230843" // resembles a SNAPSHOT
        val b = "1234567890.12345" // 10 digit number
        val c = "123456789012345.1H.5-beta" // 15 digit number
        val d = "12345678901234567890.1H.5-beta" // 20 digit number

        checkVersionsOrder(a, b)
        checkVersionsOrder(b, c)
        checkVersionsOrder(a, c)
        checkVersionsOrder(c, d)
        checkVersionsOrder(b, d)
        checkVersionsOrder(a, d)
    }

    @Test
    fun versionEqualWithLeadingZeroes() {
        checkVersionsArrayEqual(
            (1..19).map { "0".repeat(19 - it) + "1" }.toTypedArray(),
        )
    }

    @Test
    fun versionZeroEqualWithLeadingZeroes() {
        checkVersionsArrayEqual(
            (1..19).map { "0".repeat(it) }.toTypedArray(),
        )
    }

    /** MNG-6964: qualifiers starting with "-0.". */
    @Test
    fun mng6964() {
        val a = "1-0.alpha"
        val b = "1-0.beta"
        val c = "1"

        checkVersionsOrder(a, c)
        checkVersionsOrder(b, c)
        checkVersionsOrder(a, b)
    }

    @Test
    fun localeIndependent() {
        val orig = java.util.Locale.getDefault()
        val locales = listOf(java.util.Locale.ENGLISH, java.util.Locale("tr"), orig)
        try {
            for (locale in locales) {
                java.util.Locale.setDefault(locale)
                checkVersionsEqual("1-abcdefghijklmnopqrstuvwxyz", "1-ABCDEFGHIJKLMNOPQRSTUVWXYZ")
            }
        } finally {
            java.util.Locale.setDefault(orig)
        }
    }

    /** MNG-7644: 1.0.0.X1 < 1.0.0-X2 for any string X; 2.0.X == 2-X == 2.0.0.X. */
    @Test
    fun mng7644() {
        for (x in arrayOf("abc", "alpha", "a", "beta", "b", "def", "milestone", "m", "RC")) {
            checkVersionsOrder("1.0.0.${x}1", "1.0.0-${x}2")
            checkVersionsEqual("2-$x", "2.0.$x")
            checkVersionsEqual("2-$x", "2.0.0.$x")
            checkVersionsEqual("2.0.$x", "2.0.0.$x")
        }
    }

    /** MNG-7714: final-redhat < sp-redhat variants. */
    @Test
    fun mng7714() {
        val f = MavenVersion("1.0.final-redhat")
        val sp1 = MavenVersion("1.0-sp1-redhat")
        val sp2 = MavenVersion("1.0-sp-1-redhat")
        val sp3 = MavenVersion("1.0-sp.1-redhat")
        assertTrue("expected $f < $sp1", f < sp1)
        assertTrue("expected $f < $sp2", f < sp2)
        assertTrue("expected $f < $sp3", f < sp3)
    }
}
