package com.tampwell.staleguard.report

import com.google.gson.JsonParser
import com.tampwell.staleguard.security.OsvAdvisory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CycloneDxWriterTest {

    private val log4j = CycloneDxWriter.Component(
        groupId = "org.apache.logging.log4j",
        artifactId = "log4j-core",
        version = "2.14.1",
        licenses = listOf("Apache License, Version 2.0"),
        advisories = listOf(
            OsvAdvisory(
                id = "GHSA-jfh8-c2jp-5v3q",
                cveId = "CVE-2021-44228",
                severity = "CRITICAL",
                summary = "Remote code execution in Log4j",
                fixedVersion = "2.15.0",
            ),
        ),
    )

    private fun write(vararg components: CycloneDxWriter.Component) = CycloneDxWriter.write(
        projectName = "demo",
        toolVersion = "1.6.0",
        components = components.toList(),
        serialUuid = "00000000-0000-4000-8000-000000000000",
        timestampMillis = 1_755_000_000_000,
    )

    @Test
    fun `document carries the required cyclonedx envelope`() {
        val root = JsonParser.parseString(write(log4j)).asJsonObject
        assertEquals("CycloneDX", root.get("bomFormat").asString)
        assertEquals("1.5", root.get("specVersion").asString)
        assertEquals("urn:uuid:00000000-0000-4000-8000-000000000000", root.get("serialNumber").asString)
        assertEquals(1, root.get("version").asInt)
        val metadata = root.getAsJsonObject("metadata")
        assertTrue(metadata.get("timestamp").asString.endsWith("Z"))
        val tool = metadata.getAsJsonArray("tools")[0].asJsonObject
        assertEquals("Staleguard", tool.get("name").asString)
        assertEquals("1.6.0", tool.get("version").asString)
        assertEquals("demo", metadata.getAsJsonObject("component").get("name").asString)
    }

    @Test
    fun `component has maven purl and named license`() {
        val component = JsonParser.parseString(write(log4j)).asJsonObject
            .getAsJsonArray("components")[0].asJsonObject
        assertEquals("library", component.get("type").asString)
        assertEquals("pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1", component.get("purl").asString)
        assertEquals(component.get("purl").asString, component.get("bom-ref").asString)
        assertEquals(
            "Apache License, Version 2.0",
            component.getAsJsonArray("licenses")[0].asJsonObject
                .getAsJsonObject("license").get("name").asString,
        )
    }

    @Test
    fun `vulnerability links back to the component via affects ref`() {
        val root = JsonParser.parseString(write(log4j)).asJsonObject
        val vuln = root.getAsJsonArray("vulnerabilities")[0].asJsonObject
        assertEquals("GHSA-jfh8-c2jp-5v3q", vuln.get("id").asString)
        assertEquals("OSV", vuln.getAsJsonObject("source").get("name").asString)
        assertEquals("critical", vuln.getAsJsonArray("ratings")[0].asJsonObject.get("severity").asString)
        assertEquals(
            "CVE-2021-44228",
            vuln.getAsJsonArray("references")[0].asJsonObject.get("id").asString,
        )
        assertEquals(
            "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1",
            vuln.getAsJsonArray("affects")[0].asJsonObject.get("ref").asString,
        )
    }

    @Test
    fun `same coordinate from several modules appears once`() {
        val root = JsonParser.parseString(write(log4j, log4j)).asJsonObject
        assertEquals(1, root.getAsJsonArray("components").size())
        assertEquals(1, root.getAsJsonArray("vulnerabilities").size())
        assertEquals(1, root.getAsJsonArray("vulnerabilities")[0].asJsonObject.getAsJsonArray("affects").size())
    }

    @Test
    fun `clean project omits the vulnerabilities section`() {
        val clean = CycloneDxWriter.Component("com.example", "lib", "1.0")
        val root = JsonParser.parseString(write(clean)).asJsonObject
        assertNull(root.get("vulnerabilities"))
        val component = root.getAsJsonArray("components")[0].asJsonObject
        assertNull(component.get("licenses"))
    }

    @Test
    fun `unrated severity maps to unknown not low`() {
        val unrated = log4j.copy(advisories = listOf(log4j.advisories[0].copy(severity = null, cveId = null)))
        val vuln = JsonParser.parseString(write(unrated)).asJsonObject
            .getAsJsonArray("vulnerabilities")[0].asJsonObject
        assertEquals("unknown", vuln.getAsJsonArray("ratings")[0].asJsonObject.get("severity").asString)
        assertNull(vuln.get("references"))
    }

    @Test
    fun `purl percent-encodes characters outside the unreserved set`() {
        val odd = CycloneDxWriter.Component("com.example", "weird artifact", "1.0+build")
        assertEquals("pkg:maven/com.example/weird%20artifact@1.0%2Bbuild", odd.purl)
    }

    @Test
    fun `output is stable for identical input`() {
        assertEquals(write(log4j), write(log4j))
        assertFalse(write(log4j).contains("\\u003"))
    }
}
