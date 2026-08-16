package com.tampwell.staleguard.changelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseTagsTest {

    @Test
    fun `v-prefixed tag is the first candidate`() {
        assertEquals("v2.0.18", ReleaseTags.candidates("2.0.18", "slf4j-api").first())
    }

    @Test
    fun `artifact-prefixed and underscore styles are covered`() {
        val candidates = ReleaseTags.candidates("1.7.36", "slf4j-api")
        assertTrue("slf4j-api-1.7.36" in candidates)
        assertTrue("RELEASE_1_7_36" in candidates)
    }

    @Test
    fun `matching is case-insensitive`() {
        assertTrue(ReleaseTags.matches("V2.0.18", "2.0.18", null))
        assertFalse(ReleaseTags.matches("v2.0.17", "2.0.18", null))
    }

    @Test
    fun `skipped range is exclusive of current, inclusive of suggested, sorted`() {
        val range = ReleaseTags.skippedRange(
            "1.7.32",
            "2.0.0",
            listOf("2.0.0", "1.7.32", "1.7.36", "1.7.30", "2.1.0", "1.8.0-beta4"),
        )
        assertEquals(listOf("1.7.36", "1.8.0-beta4", "2.0.0"), range)
    }

    @Test
    fun `empty when already current`() {
        assertTrue(ReleaseTags.skippedRange("2.0.0", "2.0.0", listOf("1.0", "2.0.0")).isEmpty())
    }
}
