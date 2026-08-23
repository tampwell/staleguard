package com.tampwell.staleguard

/**
 * The installed plugin version, from the properties file the build expands.
 *
 * Deliberately not PluginManagerCore plus PluginId: on the 2024.3 and 2025.1
 * lines that pair compiles to a reference to PluginId.Companion, which does
 * not exist there, so it throws NoSuchFieldError for exactly the Community
 * Edition users this plugin targets. The verifier caught it; a resource read
 * cannot regress that way.
 */
object StaleguardVersion {

    private val value: String by lazy {
        StaleguardVersion::class.java.getResourceAsStream("/staleguard.properties")
            ?.use { stream -> java.util.Properties().apply { load(stream) }.getProperty("version") }
            ?: "unknown"
    }

    fun current(): String = value
}
