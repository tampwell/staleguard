package com.tampwell.staleguard.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsXmlImportTest {

    private val settingsXml = """
        <settings>
          <servers>
            <server><id>mirrored</id><username>u1</username><password>p1</password></server>
            <server><id>from-pom</id><username>u2</username><password>p2</password></server>
            <server><id>orphan</id><username>u3</username><password>p3</password></server>
            <server><id>encrypted</id><username>u4</username><password>{abc=}</password></server>
            <server><id>no-pass</id><username>u5</username></server>
          </servers>
          <mirrors>
            <mirror><id>mirrored</id><url>https://mirror.example.com/repo</url></mirror>
          </mirrors>
        </settings>
    """.trimIndent()

    private fun candidate(id: String) =
        SettingsXmlImport.candidates(
            settingsXml,
            listOf("from-pom" to "https://pom.example.org/maven"),
        ).single { it.serverId == id }

    @Test
    fun `resolves hosts from mirrors and project poms`() {
        assertEquals("mirror.example.com", candidate("mirrored").host)
        assertEquals("pom.example.org", candidate("from-pom").host)
        assertTrue(candidate("mirrored").importable)
        assertTrue(candidate("from-pom").importable)
    }

    @Test
    fun `server id with no known url is listed but not importable`() {
        val orphan = candidate("orphan")
        assertNull(orphan.host)
        assertFalse(orphan.importable)
    }

    @Test
    fun `encrypted password is never importable even with a resolved host`() {
        val settingsWithUrl = settingsXml.replace(
            "<mirror><id>mirrored</id>",
            "<mirror><id>encrypted</id>",
        )
        val enc = SettingsXmlImport.candidates(settingsWithUrl, emptyList()).single { it.serverId == "encrypted" }
        assertEquals("mirror.example.com", enc.host)
        assertTrue(enc.encrypted)
        assertFalse(enc.importable)
    }

    @Test
    fun `missing password is not importable`() {
        assertFalse(candidate("no-pass").importable)
    }

    @Test
    fun `project pom url wins over settings xml on id collision`() {
        val winner = SettingsXmlImport.candidates(
            settingsXml,
            listOf("mirrored" to "https://project.example.net/repo"),
        ).single { it.serverId == "mirrored" }
        assertEquals("project.example.net", winner.host)
    }
}
