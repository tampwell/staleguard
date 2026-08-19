package com.tampwell.staleguard.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LicensePolicyTest {

    @Test
    fun `parses deny and warn arrays from the licenses table`() {
        val policy = LicensePolicy.parse(
            """
            [ignore]
            dependencies = ["com.internal:*"]

            [licenses]
            deny = [
                "GNU General Public License*",
                "*AGPL*",
            ]
            warn = ["*Lesser General Public License*"]
            """.trimIndent(),
        )
        assertEquals(listOf("GNU General Public License*", "*AGPL*"), policy.deny)
        assertEquals(listOf("*Lesser General Public License*"), policy.warn)
    }

    @Test
    fun `missing licenses table means empty policy`() {
        val policy = LicensePolicy.parse("[ignore]\ndependencies = [\"a:b\"]")
        assertTrue(policy.isEmpty)
        assertNull(policy.deniedLicense(listOf("GNU General Public License v3.0")))
    }

    @Test
    fun `matching is case-insensitive with wildcards`() {
        val policy = LicensePolicy(deny = listOf("gnu general public license*"), warn = emptyList())
        assertEquals(
            "GNU General Public License v3.0",
            policy.deniedLicense(listOf("Apache License, Version 2.0", "GNU General Public License v3.0")),
        )
    }

    @Test
    fun `spelled-out GPL prefix does not catch the Lesser GPL`() {
        // The realistic footgun: POM strings spell licenses out. An exact
        // prefix pattern must separate GPL from LGPL.
        val policy = LicensePolicy(deny = listOf("GNU General Public License*"), warn = emptyList())
        assertNull(policy.deniedLicense(listOf("GNU Lesser General Public License v2.1")))
        assertEquals(
            "GNU General Public License v3.0",
            policy.deniedLicense(listOf("GNU General Public License v3.0")),
        )
    }

    @Test
    fun `broad pattern intentionally catches both GPL and LGPL`() {
        val policy = LicensePolicy(deny = listOf("*General Public License*"), warn = emptyList())
        assertEquals(
            "GNU Lesser General Public License v2.1",
            policy.deniedLicense(listOf("GNU Lesser General Public License v2.1")),
        )
    }

    @Test
    fun `deny and warn are independent lists`() {
        val policy = LicensePolicy(
            deny = listOf("*AGPL*"),
            warn = listOf("*Server Side Public License*"),
        )
        val licenses = listOf("Server Side Public License, v 1")
        assertNull(policy.deniedLicense(licenses))
        assertEquals("Server Side Public License, v 1", policy.warnedLicense(licenses))
    }

    @Test
    fun `tomlStringArray reads only the requested table and key`() {
        val text = """
            [licenses]
            deny = ["A"]
            warn = ["B", "C"]

            [ignore]
            dependencies = ["x:y"]
        """.trimIndent()
        assertEquals(listOf("A"), IgnoreRules.tomlStringArray(text, "licenses", "deny"))
        assertEquals(listOf("B", "C"), IgnoreRules.tomlStringArray(text, "licenses", "warn"))
        assertEquals(listOf("x:y"), IgnoreRules.tomlStringArray(text, "ignore", "dependencies"))
        assertEquals(emptyList<String>(), IgnoreRules.tomlStringArray(text, "missing", "deny"))
    }
}
