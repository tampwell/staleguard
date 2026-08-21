package com.tampwell.staleguard

/** The installed plugin version, baked into staleguard.properties at build time. */
object StaleguardVersion {
    private val value: String by lazy {
        StaleguardVersion::class.java.getResourceAsStream("/staleguard.properties")
            ?.use { stream -> java.util.Properties().apply { load(stream) }.getProperty("version") }
            ?: "dev"
    }

    fun current(): String = value
}
