package com.tampwell.staleguard.impact

import java.io.File
import java.nio.charset.StandardCharsets
import java.text.MessageFormat
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The impact verdicts use MessageFormat choice syntax for pluralisation, which
 * fails only at format time — inside the dialog, in front of the user. Reading
 * the bundle straight off disk and formatting it here turns that into a test
 * failure, and pins the singular wording, which is the form that reads wrong
 * if nobody checks it.
 */
class ImpactMessagesTest {

    // Read as UTF-8, which is how the platform loads bundles. Properties.load
    // on a raw InputStream decodes ISO-8859-1 and would turn this file's arrows
    // and dashes into mojibake, testing an encoding no user ever sees.
    private val bundle = Properties().apply {
        File("src/main/resources/messages/StaleguardBundle.properties").inputStream().use { stream ->
            load(stream.reader(StandardCharsets.UTF_8))
        }
    }

    private fun format(key: String, vararg args: Any): String =
        MessageFormat.format(checkNotNull(bundle.getProperty(key)) { "missing key: $key" }, *args)

    @Test
    fun `the breaking verdict reads correctly for one member at one call site`() {
        assertEquals(
            "Your code calls 1 removed member at 1 call site. This upgrade removes 1 public member in total.",
            format("impact.verdict.breaks", 1, 1, 1),
        )
    }

    @Test
    fun `the breaking verdict pluralises every count independently`() {
        assertEquals(
            "Your code calls 2 removed members at 5 call sites. This upgrade removes 182 public members in total.",
            format("impact.verdict.breaks", 2, 5, 182),
        )
    }

    @Test
    fun `the unused verdict says it rather than any of them for a single removal`() {
        assertEquals(
            "This upgrade removes 1 public member. Your code does not call it.",
            format("impact.verdict.unused", 1),
        )
    }

    @Test
    fun `the unused verdict pluralises for many removals`() {
        assertEquals(
            "This upgrade removes 197 public members. Your code does not call any of them.",
            format("impact.verdict.unused", 197),
        )
    }

    @Test
    fun `the tree root pluralises both counts`() {
        assertEquals(
            "1 removed member used, 1 call site — double-click to open",
            format("impact.tree.root", 1, 1),
        )
        assertEquals(
            "3 removed members used, 7 call sites — double-click to open",
            format("impact.tree.root", 3, 7),
        )
    }

    @Test
    fun `apostrophes in the incomplete notices survive MessageFormat`() {
        assertEquals(
            "Could not read the binary for g:a 1.0. It is not on this project's classpath " +
                "and no configured repository served it.",
            format("impact.incomplete.current", "g:a", "1.0", "2.0"),
        )
        assertEquals(
            "Offline mode is on, so the new version's binary could not be downloaded. " +
                "Turn it off in Settings to run this check.",
            format("impact.incomplete.offline", "g:a", "1.0", "2.0"),
        )
    }

    @Test
    fun `the candidate notice names the version that could not be downloaded`() {
        assertEquals(
            "Could not download the binary for g:a 2.0 from any configured repository, " +
                "so the comparison did not run.",
            format("impact.incomplete.candidate", "g:a", "1.0", "2.0"),
        )
    }
}
