package com.tampwell.staleguard.actions

import org.junit.Assert.assertEquals
import org.junit.Test

class BatchImpactLabelTest {

    private val row = "app: com.google.code.gson:gson  2.8.9 -> 2.11.0  [confidence 85/100] - Safe to update"

    @Test
    fun `a verdict is appended after the row text`() {
        assertEquals(
            "$row  [checked: safe]",
            BatchUpdateDialog.withVerdict(row, "[checked: safe]"),
        )
    }

    @Test
    fun `re-checking replaces the verdict instead of stacking a second one`() {
        val once = BatchUpdateDialog.withVerdict(row, "[checked: safe]")
        val twice = BatchUpdateDialog.withVerdict(once, "[checked: breaks 2 members you call]")

        assertEquals("$row  [checked: breaks 2 members you call]", twice)
    }

    @Test
    fun `a clean verdict can replace a breaking one after the code was fixed`() {
        val broken = BatchUpdateDialog.withVerdict(row, "[checked: breaks 1 member you call]")

        assertEquals("$row  [checked: safe]", BatchUpdateDialog.withVerdict(broken, "[checked: safe]"))
    }
}
