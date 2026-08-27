package com.tampwell.staleguard.impact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpactMarkdownTest {

    private fun report(
        usages: List<RemovedUsage> = emptyList(),
        removedTotal: Int = 0,
        incomplete: ImpactReport.Incomplete? = null,
        truncated: Boolean = false,
    ) = ImpactReport("com.fasterxml.jackson.core:jackson-databind", "2.13.0", "2.19.0", removedTotal, usages, incomplete, truncated)

    private val usage = RemovedUsage(
        MemberRef("com/fasterxml/jackson/databind/ObjectMapper", "setDateFormat", "(Ljava/text/DateFormat;)V"),
        listOf(
            UsageLocation("file:///p/src/main/java/app/UserService.java", "src/main/java/app/UserService.java", 42, 900),
            UsageLocation("file:///p/src/main/java/app/Config.java", "src/main/java/app/Config.java", 7, 120),
        ),
    )

    @Test
    fun `a breaking report lists every member and call site`() {
        val markdown = ImpactMarkdown.render(report(usages = listOf(usage), removedTotal = 182))

        assertTrue(markdown.contains("### Upgrade impact: com.fasterxml.jackson.core:jackson-databind 2.13.0 -> 2.19.0"))
        assertTrue(markdown.contains("Calls **1** removed member at **2** call sites"))
        assertTrue(markdown.contains("- `ObjectMapper.setDateFormat(DateFormat)`"))
        assertTrue(markdown.contains("  - src/main/java/app/UserService.java:42"))
        assertTrue(markdown.contains("  - src/main/java/app/Config.java:7"))
        assertTrue(markdown.contains("182 public members removed in total"))
    }

    @Test
    fun `a clean report says none of them, with correct singulars`() {
        assertTrue(
            ImpactMarkdown.render(report(removedTotal = 1))
                .contains("Removes 1 public member; this project calls none of them."),
        )
        assertTrue(
            ImpactMarkdown.render(report(removedTotal = 33))
                .contains("Removes 33 public members; this project calls none of them."),
        )
    }

    @Test
    fun `no removals reads as binary compatible`() {
        assertTrue(ImpactMarkdown.render(report()).contains("Removes no public API."))
    }

    @Test
    fun `an incomplete analysis exports as incomplete, never as a conclusion`() {
        val markdown = ImpactMarkdown.render(report(incomplete = ImpactReport.Incomplete.OFFLINE))

        assertTrue(markdown.contains("Analysis incomplete; no conclusion."))
        assertFalse(markdown.contains("Removes"))
        assertFalse(markdown.contains("Calls"))
    }

    @Test
    fun `a truncated search is disclosed in the export`() {
        val markdown = ImpactMarkdown.render(report(usages = listOf(usage), removedTotal = 9000, truncated = true))

        assertTrue(markdown.contains("_Search truncated; the list above may be incomplete._"))
    }

    @Test
    fun `output ends with exactly one newline for clean pasting`() {
        val markdown = ImpactMarkdown.render(report(removedTotal = 5))

        assertTrue(markdown.endsWith("\n"))
        assertFalse(markdown.endsWith("\n\n"))
    }

    @Test
    fun `the attribution line is present so pasted reports name their source`() {
        assertEquals(
            1,
            Regex("Staleguard upgrade impact check").findAll(ImpactMarkdown.render(report())).count(),
        )
    }
}
