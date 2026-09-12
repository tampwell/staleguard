package com.tampwell.staleguard.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `valueRange spans exactly the quoted value`() {
        val text = """
            object Versions {
                const val gson = "2.10.1"
                const val kotlin = "1.9.24"
            }
            """.trimIndent()

        val range = checkNotNull(BuildSrcVersions.valueRange(text, "Versions.kotlin"))

        assertEquals("1.9.24", text.substring(range.first, range.last + 1))
        // Replacing through the range produces the bumped file verbatim.
        val edited = text.replaceRange(range, "2.0.0")
        assertEquals("2.0.0", BuildSrcVersions.parse(edited)["Versions.kotlin"])
        assertEquals("2.10.1", BuildSrcVersions.parse(edited)["Versions.gson"])
    }

    @Test
    fun `valueRange accepts a bare name and misses honestly`() {
        val text = "object Versions {\n    const val gson = \"2.10.1\"\n}"

        val bare = checkNotNull(BuildSrcVersions.valueRange(text, "gson"))
        assertEquals("2.10.1", text.substring(bare.first, bare.last + 1))

        assertNull(BuildSrcVersions.valueRange(text, "Versions.absent"))
        assertNull(BuildSrcVersions.valueRange("object Libs { const val gson = \"1\" }", "gson"))
    }

    @Test
    fun `valueRange never targets a templated or dynamic constant`() {
        val text = """
            object Versions {
                const val templated = "prefix-${'$'}{gson}"
                val dynamic = computeSomething()
            }
            """.trimIndent()

        assertNull(BuildSrcVersions.valueRange(text, "Versions.templated"))
        assertNull(BuildSrcVersions.valueRange(text, "Versions.dynamic"))
    }
}
