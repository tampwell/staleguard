package com.tampwell.staleguard.repository

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.tampwell.staleguard.settings.StaleguardSettings

/**
 * Credential store roundtrip against the platform's in-memory test
 * PasswordSafe. Calls run on a pooled thread because the production contract
 * is background-only.
 */
class RepositoryCredentialsPlatformTest : BasePlatformTestCase() {

    private fun <T> onPooledThread(block: () -> T): T =
        ApplicationManager.getApplication().executeOnPooledThread(block).get()

    override fun tearDown() {
        try {
            onPooledThread {
                val service = RepositoryCredentials.getInstance()
                service.configuredHosts().forEach(service::remove)
            }
        } finally {
            super.tearDown()
        }
    }

    fun testRoundTripAndHostGating() {
        val service = RepositoryCredentials.getInstance()

        onPooledThread { service.set("Nexus.MyCompany.com", "builder", "s3cret".toCharArray()) }

        // Host list persisted, normalized to lowercase, secrets not in it.
        assertEquals(listOf("nexus.mycompany.com"), service.configuredHosts())
        assertFalse(StaleguardSettings.getInstance().state.credentialHosts.toString().contains("s3cret"))

        val credentials = onPooledThread { service.forUrl("https://nexus.mycompany.com/repository/maven-public/g/a/maven-metadata.xml") }
        assertEquals("builder", credentials!!.userName)
        assertEquals("s3cret", credentials.getPasswordAsString())

        // A host the user never configured gets nothing, even if resolvable.
        assertNull(onPooledThread { service.forUrl("https://other-repo.example.com/metadata.xml") })

        assertEquals("builder", onPooledThread { service.usernameFor("nexus.mycompany.com") })

        onPooledThread { service.remove("nexus.mycompany.com") }
        assertTrue(service.configuredHosts().isEmpty())
        assertNull(onPooledThread { service.forUrl("https://nexus.mycompany.com/repository/maven-public/metadata.xml") })
    }
}
