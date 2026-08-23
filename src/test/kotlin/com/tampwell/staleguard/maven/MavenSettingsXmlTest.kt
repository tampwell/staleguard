package com.tampwell.staleguard.maven

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MavenSettingsXmlTest {

    @Test
    fun `parses servers with mirrors and profile repositories`() {
        val parsed = MavenSettingsXml.parse(
            """
            <settings>
              <servers>
                <server>
                  <id>corp-nexus</id>
                  <username>deploy</username>
                  <password>s3cret</password>
                </server>
                <server>
                  <id>no-creds</id>
                </server>
              </servers>
              <mirrors>
                <mirror>
                  <id>corp-nexus</id>
                  <mirrorOf>central</mirrorOf>
                  <url>https://nexus.corp.example/repository/maven-public/</url>
                </mirror>
              </mirrors>
              <profiles>
                <profile>
                  <id>dev</id>
                  <repositories>
                    <repository>
                      <id>staging</id>
                      <url>https://staging.corp.example/maven</url>
                    </repository>
                  </repositories>
                </profile>
              </profiles>
            </settings>
            """.trimIndent(),
        )
        assertEquals(listOf("corp-nexus", "no-creds"), parsed.servers.map { it.id })
        assertEquals("deploy", parsed.servers[0].username)
        assertEquals("s3cret", parsed.servers[0].password)
        assertFalse(parsed.servers[0].encrypted)
        assertEquals(
            listOf(
                "corp-nexus" to "https://nexus.corp.example/repository/maven-public/",
                "staging" to "https://staging.corp.example/maven",
            ),
            parsed.repoUrls.map { it.id to it.url },
        )
    }

    @Test
    fun `braced password is flagged encrypted`() {
        val parsed = MavenSettingsXml.parse(
            """
            <settings><servers><server>
              <id>enc</id>
              <username>u</username>
              <password>{COQLCE6DU6GtcS5P=}</password>
            </server></servers></settings>
            """.trimIndent(),
        )
        assertTrue(parsed.servers.single().encrypted)
    }

    @Test
    fun `malformed xml yields empty parse instead of throwing`() {
        val parsed = MavenSettingsXml.parse("<settings><servers></settings>")
        assertTrue(parsed.servers.isEmpty())
        assertTrue(parsed.repoUrls.isEmpty())
    }

    @Test
    fun `server without id is skipped`() {
        val parsed = MavenSettingsXml.parse(
            "<settings><servers><server><username>u</username></server></servers></settings>",
        )
        assertTrue(parsed.servers.isEmpty())
    }
}
