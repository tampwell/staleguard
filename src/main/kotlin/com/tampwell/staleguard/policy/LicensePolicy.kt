package com.tampwell.staleguard.policy

/**
 * Committed license rules from `.staleguard.toml`:
 * ```
 * [licenses]
 * deny = ["GNU General Public License*", "*AGPL*"]
 * warn = ["*Lesser General Public License*", "Server Side Public License*"]
 * ```
 * Patterns match the license names as published in the artifact's POM, with
 * `*` wildcards, case-insensitively — POM license strings are free text
 * ("Apache License, Version 2.0", "The Apache Software License, Version 2.0",
 * ...), so exact matching would be useless. Deny wins over warn.
 *
 * Careful pattern advice lives in the docs: the acronym "GPL" does not appear
 * in the spelled-out "GNU General Public License v3.0", and a broad
 * "*General Public License*" also catches the LESSER GPL — patterns should be
 * written against real POM strings.
 */
data class LicensePolicy(val deny: List<String>, val warn: List<String>) {

    val isEmpty: Boolean get() = deny.isEmpty() && warn.isEmpty()

    /** First license matching a deny pattern, or null. */
    fun deniedLicense(licenses: List<String>): String? = firstMatch(deny, licenses)

    /** First license matching a warn pattern (deny checked by the caller first). */
    fun warnedLicense(licenses: List<String>): String? = firstMatch(warn, licenses)

    private fun firstMatch(patterns: List<String>, licenses: List<String>): String? =
        licenses.firstOrNull { license -> patterns.any { glob(it, license) } }

    private fun glob(pattern: String, value: String): Boolean {
        val regex = pattern.lowercase().split('*').joinToString(".*") { Regex.escape(it) }
        return Regex("^$regex$").matches(value.lowercase())
    }

    companion object {
        val EMPTY = LicensePolicy(emptyList(), emptyList())

        fun parse(tomlText: String): LicensePolicy = LicensePolicy(
            deny = IgnoreRules.tomlStringArray(tomlText, "licenses", "deny"),
            warn = IgnoreRules.tomlStringArray(tomlText, "licenses", "warn"),
        )
    }
}
