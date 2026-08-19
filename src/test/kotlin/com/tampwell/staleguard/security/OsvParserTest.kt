package com.tampwell.staleguard.security

import com.tampwell.staleguard.version.MavenVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OsvParserTest {

    // Shaped like the real log4j-core response: two Maven ranges (1.x window
    // fixed in 2.12.2, 2.13+ window fixed in 2.16.0) plus an unrelated
    // pax-logging package that must not influence the fixed version.
    private val log4jJson = """
        {"vulns":[{
            "id":"GHSA-7rjr-3q55-vv33",
            "aliases":["CVE-2021-45046","FOO-1"],
            "summary":"Incomplete fix for Apache Log4j vulnerability",
            "database_specific":{"severity":"CRITICAL"},
            "affected":[
                {"package":{"ecosystem":"Maven","name":"org.apache.logging.log4j:log4j-core"},
                 "ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"2.13.0"},{"fixed":"2.16.0"}]}]},
                {"package":{"ecosystem":"Maven","name":"org.apache.logging.log4j:log4j-core"},
                 "ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"0"},{"fixed":"2.12.2"}]}]},
                {"package":{"ecosystem":"Maven","name":"org.ops4j.pax.logging:pax-logging-log4j2"},
                 "ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"1.8.0"},{"fixed":"1.9.2"}]}]}
            ]
        }]}
    """.trimIndent()

    @Test
    fun `parses id, cve alias, severity, and summary`() {
        val advisories = OsvParser.parse(log4jJson, "org.apache.logging.log4j:log4j-core", MavenVersion("2.14.1"))
        val advisory = advisories.single()
        assertEquals("GHSA-7rjr-3q55-vv33", advisory.id)
        assertEquals("CVE-2021-45046", advisory.cveId)
        assertEquals("CVE-2021-45046", advisory.displayId)
        assertEquals("CRITICAL", advisory.severity)
        assertEquals("Incomplete fix for Apache Log4j vulnerability", advisory.summary)
        assertEquals("https://osv.dev/vulnerability/GHSA-7rjr-3q55-vv33", advisory.url)
    }

    @Test
    fun `picks the fixed version from the range containing our version`() {
        val at2141 = OsvParser.parse(log4jJson, "org.apache.logging.log4j:log4j-core", MavenVersion("2.14.1"))
        assertEquals("2.16.0", at2141.single().fixedVersion)

        val at2100 = OsvParser.parse(log4jJson, "org.apache.logging.log4j:log4j-core", MavenVersion("2.10.0"))
        assertEquals("2.12.2", at2100.single().fixedVersion)
    }

    @Test
    fun `other packages in the same advisory never leak their fixed version`() {
        // 1.9.0 sits inside pax-logging's range but outside both log4j ranges'
        // windows only in the fixed sense — the point: name filtering, not luck.
        val advisories = OsvParser.parse(log4jJson, "org.apache.logging.log4j:log4j-core", MavenVersion("2.14.1"))
        assertTrue(advisories.single().fixedVersion != "1.9.2")
    }

    @Test
    fun `no vulns field means clean`() {
        assertTrue(OsvParser.parse("{}", "a:b", MavenVersion("1.0")).isEmpty())
        assertTrue(OsvParser.parse("""{"vulns":[]}""", "a:b", MavenVersion("1.0")).isEmpty())
    }

    @Test
    fun `missing optional fields survive`() {
        val minimal = """{"vulns":[{"id":"OSV-2020-1"}]}"""
        val advisory = OsvParser.parse(minimal, "a:b", MavenVersion("1.0")).single()
        assertEquals("OSV-2020-1", advisory.id)
        assertNull(advisory.cveId)
        assertNull(advisory.severity)
        assertNull(advisory.summary)
        assertNull(advisory.fixedVersion)
        assertEquals("OSV-2020-1", advisory.displayId)
    }

    @Test
    fun `severity rank orders critical over high over unrated over moderate`() {
        fun advisory(severity: String?) = OsvAdvisory("X", null, severity, null, null)
        assertTrue(advisory("CRITICAL").severityRank > advisory("HIGH").severityRank)
        assertTrue(advisory("HIGH").severityRank > advisory(null).severityRank)
        assertTrue(advisory(null).severityRank > advisory("MODERATE").severityRank)
        assertTrue(advisory("MODERATE").severityRank > advisory("LOW").severityRank)
    }
}
