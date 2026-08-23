package com.tampwell.staleguard.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * User-facing knobs, persisted IDE-wide in staleguard.xml (roaming-safe
 * simple values only). Defaults are the product decisions of 2026-08-13:
 * stable-only suggestions, 2-year abandonment threshold.
 *
 * NOTE: settings never collect or transmit anything — hard project rule.
 */
@State(name = "StaleguardSettings", storages = [Storage("staleguard.xml")])
@Service(Service.Level.APP)
class StaleguardSettings : PersistentStateComponent<StaleguardSettings.State> {

    class State {
        var offlineMode: Boolean = false
        var suggestPrereleases: Boolean = false
        var abandonmentEnabled: Boolean = true
        var abandonmentYears: Int = 2

        /**
         * Queries osv.dev with artifact coordinates — the same class of
         * traffic as version lookups, but a different host, so it gets its
         * own switch. Honors offlineMode regardless.
         */
        var vulnerabilityChecksEnabled: Boolean = true

        /**
         * Route Central lookups through the mirrors in ~/.m2/settings.xml the
         * way Maven would. On by default: in a mirrored environment, going
         * direct is exactly what breaks.
         */
        var useMavenMirrors: Boolean = true
        var ignoredCoordinates: MutableList<String> = mutableListOf()

        /** Properties the user opted out of blast-radius confirmation for. */
        var suppressedPropertyWarnings: MutableList<String> = mutableListOf()

        /**
         * Hosts with repository credentials configured. HOSTS ONLY — the
         * secrets live in the PasswordSafe, never in this roaming-safe XML.
         */
        var credentialHosts: MutableList<String> = mutableListOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun isIgnored(groupId: String, artifactId: String): Boolean =
        state.ignoredCoordinates.any { it.trim() == "$groupId:$artifactId" }

    companion object {
        fun getInstance(): StaleguardSettings = service()
    }
}
