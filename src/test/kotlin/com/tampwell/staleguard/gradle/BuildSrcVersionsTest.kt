package com.tampwell.staleguard.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildSrcVersionsTest {

    @Test
    fun `const vals inside object Versions parse with prefixed keys`() {
        val values = BuildSrcVersions.parse(
            """
            package deps

            object Versions {
                const val gson = "2.10.1"
                const val kotlin = "1.9.24"
                val dynamic = computeSomething()
                const val templated = "prefix-${'$'}{gson}"
            }

            object Libraries {
                const val notAVersion = "com.example:lib"
            }
            """.trimIndent(),
        )
        assertEquals("2.10.1", values["Versions.gson"])
        assertEquals("1.9.24", values["Versions.kotlin"])
        assertEquals(2, values.size)
    }

    @Test
    fun `file without a Versions object yields nothing`() {
        assertTrue(BuildSrcVersions.parse("object Libs { const val x = \"1\" }").isEmpty())
    }
}
