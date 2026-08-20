package com.tampwell.staleguard.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepositoryCredentialsTest {

    @Test
    fun `hostOf extracts the lowercased host component`() {
        assertEquals("nexus.mycompany.com", RepositoryCredentials.hostOf("https://Nexus.MyCompany.com/repository/maven-public"))
        assertEquals("nexus.mycompany.com", RepositoryCredentials.hostOf("https://nexus.mycompany.com:8081/repo"))
        assertEquals("jitpack.io", RepositoryCredentials.hostOf("https://jitpack.io"))
    }

    @Test
    fun `hostOf returns null for garbage`() {
        assertNull(RepositoryCredentials.hostOf("not a url at all"))
        assertNull(RepositoryCredentials.hostOf(""))
    }

    @Test
    fun `basicAuthValue matches the RFC 7617 example`() {
        assertEquals(
            "Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==",
            RepositoryCredentials.basicAuthValue("Aladdin", "open sesame".toCharArray()),
        )
    }

    @Test
    fun `basicAuthValue handles non-ascii credentials as utf8`() {
        // UTF-8 is the de facto interpretation modern repo managers use.
        assertEquals(
            "Basic dXNlcjpww6Rzc3fDtnJk",
            RepositoryCredentials.basicAuthValue("user", "pässwörd".toCharArray()),
        )
    }
}
