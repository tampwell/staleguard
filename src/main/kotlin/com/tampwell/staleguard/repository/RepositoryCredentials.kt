package com.tampwell.staleguard.repository

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-host repository credentials for private Nexus/Artifactory instances.
 *
 * SECURITY INVARIANTS (do not weaken):
 *  - Secrets live ONLY in the IDE's PasswordSafe (OS keychain / KeePass) —
 *    never in settings XML, never in logs, never in exports.
 *  - Credentials are sent ONLY to hosts the user explicitly configured, and
 *    only over the URL the build file already declares. No guessing, no
 *    wildcards, no auto-import from settings.xml.
 *  - The non-secret HOST LIST is persisted in StaleguardSettings so the
 *    settings page can enumerate entries (PasswordSafe cannot list keys).
 *
 * The in-memory cache exists because keychain access can prompt or block;
 * one read per host per session is the budget. All PasswordSafe traffic is
 * background-thread only, per the platform contract.
 */
@Service(Service.Level.APP)
class RepositoryCredentials {

    private val cache = ConcurrentHashMap<String, Optional>(4)

    /** ConcurrentHashMap disallows null values; this is the standard workaround. */
    private data class Optional(val credentials: Credentials?)

    /**
     * Credentials for the host of [url], or null when the user configured
     * none. Warm after the first call per host.
     */
    @RequiresBackgroundThread
    fun forUrl(url: String): Credentials? {
        val host = hostOf(url) ?: return null
        if (host !in configuredHosts()) return null
        return cache.getOrPut(host) { Optional(PasswordSafe.instance.get(attributesFor(host))) }.credentials
    }

    fun configuredHosts(): List<String> =
        com.tampwell.staleguard.settings.StaleguardSettings.getInstance().state.credentialHosts.toList()

    @RequiresBackgroundThread
    fun set(host: String, username: String, password: CharArray) {
        val normalized = host.trim().lowercase()
        PasswordSafe.instance.set(attributesFor(normalized), Credentials(username, password))
        val hosts = com.tampwell.staleguard.settings.StaleguardSettings.getInstance().state.credentialHosts
        if (normalized !in hosts) hosts.add(normalized)
        cache.remove(normalized)
    }

    @RequiresBackgroundThread
    fun remove(host: String) {
        val normalized = host.trim().lowercase()
        PasswordSafe.instance.set(attributesFor(normalized), null)
        com.tampwell.staleguard.settings.StaleguardSettings.getInstance().state.credentialHosts.remove(normalized)
        cache.remove(normalized)
    }

    /** For the settings UI only — shows which username an entry carries. */
    @RequiresBackgroundThread
    fun usernameFor(host: String): String? =
        PasswordSafe.instance.get(attributesFor(host.trim().lowercase()))?.userName

    private fun attributesFor(host: String): CredentialAttributes =
        CredentialAttributes(generateServiceName("Staleguard", host))

    companion object {
        fun getInstance(): RepositoryCredentials = service()

        /** Pure and testable: the host component, lowercased, or null for garbage. */
        fun hostOf(url: String): String? = try {
            URI(url).host?.lowercase()
        } catch (_: Exception) {
            null
        }

        /** RFC 7617 Basic auth header value. Never log the return value. */
        fun basicAuthValue(username: String, password: CharArray): String {
            val raw = "$username:${String(password)}"
            return "Basic " + java.util.Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
        }
    }
}
