package com.tampwell.staleguard.repository

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MavenMetadataTest {

    private fun metadata(
        versions: List<String>,
        lastUpdated: String? = "20260810120000",
    ): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("<metadata><groupId>com.example</groupId><artifactId>demo</artifactId><versioning>")
        append("<latest>${versions.lastOrNull().orEmpty()}</latest>")
        append("<release>${versions.lastOrNull().orEmpty()}</release>")
        append("<versions>")
        versions.forEach { append("<version>$it</version>") }
        append("</versions>")
        if (lastUpdated != null) append("<lastUpdated>$lastUpdated</lastUpdated>")
        append("</versioning></metadata>")
    }

    @Test
    fun `parses coordinates versions and lastUpdated`() {
        val m = MavenMetadata.parse(metadata(listOf("1.0", "1.1", "2.0")))
        assertEquals("com.example", m.groupId)
        assertEquals("demo", m.artifactId)
        assertEquals(3, m.versions.size)
        assertEquals(LocalDateTime.of(2026, 8, 10, 12, 0, 0), m.lastUpdatedUtc)
    }

    @Test
    fun `latest uses Maven ordering not file order`() {
        val m = MavenMetadata.parse(metadata(listOf("2.0", "1.9", "2.0-rc1", "1.10")))
        assertEquals("2.0", m.latest?.value)
    }

    @Test
    fun `latestStable skips snapshots and prereleases`() {
        val m = MavenMetadata.parse(
            metadata(listOf("1.0", "1.1", "2.0-SNAPSHOT", "2.0-rc1", "2.0-beta2", "2.0-alpha1", "2.0-M3")),
        )
        assertEquals("1.1", m.latestStable?.value)
        assertEquals("2.0-SNAPSHOT", m.latest?.value)
    }

    @Test
    fun `latestStable keeps Final and GA and sp releases`() {
        val m = MavenMetadata.parse(metadata(listOf("5.0.0.Final", "5.0.1.Final", "4.9-ga", "5.0.0-sp1")))
        assertEquals("5.0.1.Final", m.latestStable?.value)
    }

    @Test
    fun `stability check is not fooled by artifact names containing markers`() {
        // "1.2.3-jre" must be stable; canonical form matters, not the raw string
        val m = MavenMetadata.parse(metadata(listOf("31.0.1-jre", "33.0.0-jre")))
        assertEquals("33.0.0-jre", m.latestStable?.value)
    }

    @Test
    fun `qualifiers merely containing marker substrings stay stable`() {
        // "arch" contains "rc", "search" contains "rc" — neither is a prerelease
        val m = MavenMetadata.parse(metadata(listOf("2.0-arch", "2.1-search", "2.0-rc1")))
        assertEquals("2.1-search", m.latestStable?.value)
    }

    @Test
    fun `aliased single-letter prereleases are recognized`() {
        // 3.0a1 = alpha-1, 3.0b2 = beta-2, 3.0M3 = milestone-3
        val m = MavenMetadata.parse(metadata(listOf("2.9", "3.0a1", "3.0b2", "3.0M3")))
        assertEquals("2.9", m.latestStable?.value)
    }

    @Test
    fun `empty versions list yields null latest`() {
        val m = MavenMetadata.parse(metadata(emptyList()))
        assertTrue(m.versions.isEmpty())
        assertNull(m.latest)
        assertNull(m.latestStable)
    }

    @Test
    fun `missing lastUpdated is tolerated`() {
        val m = MavenMetadata.parse(metadata(listOf("1.0"), lastUpdated = null))
        assertNull(m.lastUpdatedUtc)
    }

    @Test
    fun `garbage lastUpdated is tolerated`() {
        val m = MavenMetadata.parse(metadata(listOf("1.0"), lastUpdated = "not-a-date"))
        assertNull(m.lastUpdatedUtc)
    }

    @Test
    fun `malformed xml throws MetadataParseException`() {
        assertThrows(MetadataParseException::class.java) {
            MavenMetadata.parse("<metadata><versioning>")
        }
    }

    @Test
    fun `doctype is rejected - XXE hardening`() {
        val evil = """<?xml version="1.0"?><!DOCTYPE metadata [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <metadata><versioning><versions><version>&x;</version></versions></versioning></metadata>"""
        assertThrows(MetadataParseException::class.java) { MavenMetadata.parse(evil) }
    }

    @Test
    fun `real-world guava-shaped metadata parses`() {
        val m = MavenMetadata.parse(
            metadata(listOf("31.0.1-android", "31.0.1-jre", "32.1.3-android", "32.1.3-jre", "33.4.8-android", "33.4.8-jre")),
        )
        assertEquals("33.4.8-jre", m.latestStable?.value)
    }
}
