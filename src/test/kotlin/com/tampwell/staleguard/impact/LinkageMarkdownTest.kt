package com.tampwell.staleguard.impact

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LinkageMarkdownTest {

    private val broken = LinkageAudit.BrokenRef(
        "app.jar",
        MemberRef("com/fasterxml/jackson/core/JsonParser", "streamReadConstraints", "()Lcom/fasterxml/jackson/core/StreamReadConstraints;"),
        "jackson-core-2.13.0.jar",
    )

    @Test
    fun `a clean report says so with the numbers that earn the claim`() {
        val markdown = LinkageMarkdown.render(
            LinkageAudit.Report(jarCount = 28, classCount = 1000, refCount = 977057, brokenMembers = emptyList(), evictedClasses = emptyList()),
        )

        assertTrue(markdown.contains("Every call across 28 classpath entries resolves (977057 references checked)."))
        assertTrue(markdown.contains("Nothing will fail to link."))
    }

    @Test
    fun `findings name the caller, the member, and the jar whose version has to move`() {
        val markdown = LinkageMarkdown.render(
            LinkageAudit.Report(
                jarCount = 3,
                classCount = 900,
                refCount = 22902,
                brokenMembers = listOf(broken),
                evictedClasses = listOf(LinkageAudit.EvictedClassRefs("app.jar", "com/fasterxml/jackson/core/StreamReadConstraints", 16)),
            ),
        )

        assertTrue(markdown.contains("**1** call cannot resolve"))
        assertTrue(markdown.contains("- `app.jar`"))
        assertTrue(markdown.contains("`JsonParser.streamReadConstraints()` missing from the resolved `jackson-core-2.13.0.jar`"))
        assertTrue(markdown.contains("`com.fasterxml.jackson.core.StreamReadConstraints` is not on the classpath (16 references from `app.jar`)"))
    }

    @Test
    fun `duplicate call sites collapse to one line per member`() {
        val markdown = LinkageMarkdown.render(
            LinkageAudit.Report(3, 900, 22902, brokenMembers = listOf(broken, broken, broken), evictedClasses = emptyList()),
        )

        assertEquals(1, Regex("streamReadConstraints").findAll(markdown).count())
        assertTrue(markdown.contains("**3** calls cannot resolve"))
    }

    @Test
    fun `the own-code standing is stated in every export`() {
        val clean = LinkageAudit.Report(2, 10, 100, emptyList(), emptyList())

        assertTrue(
            LinkageMarkdown.render(clean, OwnCodeAudit.Standing.Built(asOfMillis = 1000))
                .contains("_Includes your own compiled classes, as of the last build._"),
        )
        assertTrue(
            LinkageMarkdown.render(clean, OwnCodeAudit.Standing.PartiallyBuilt(listOf("web", "cli"), 1000))
                .contains("EXCEPT unbuilt modules web, cli; their calls were not checked."),
        )
        assertTrue(
            LinkageMarkdown.render(clean, OwnCodeAudit.Standing.NothingBuilt)
                .contains("_Jars only; no compiled project output was found to include._"),
        )
    }

    @Test
    fun `the fix block names the bump or the honest absence of one`() {
        val markdown = LinkageMarkdown.render(
            LinkageAudit.Report(3, 900, 22902, listOf(broken), emptyList()),
            OwnCodeAudit.Standing.NothingBuilt,
            mapOf(
                "jackson-core-2.13.0.jar" to FixSuggestions.Suggestion.FixedIn("2.15.0"),
                "vendored.jar" to FixSuggestions.Suggestion.NoCleanVersion,
            ),
        )

        assertTrue(markdown.contains("**The fix:**"))
        assertTrue(markdown.contains("- bump `jackson-core-2.13.0.jar` to **2.15.0** or later"))
        assertTrue(markdown.contains("- `vendored.jar`: no released version satisfies every call"))
    }

    @Test
    fun `output ends with exactly one newline and carries the attribution line`() {
        val markdown = LinkageMarkdown.render(LinkageAudit.Report(1, 1, 1, emptyList(), emptyList()))

        assertTrue(markdown.endsWith("check, resolved at the bytecode level._\n"))
    }
}

class JarScannerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun ownClassBytes(internalName: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/$internalName.class")).use { it.readBytes() }

    private fun jarOf(entries: Map<String, ByteArray>): Path {
        val jar = temp.newFile("fixture.jar").toPath()
        ZipOutputStream(Files.newOutputStream(jar)).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return jar
    }

    @Test
    fun `scans every class with refs and full declarations`() {
        val jar = jarOf(
            mapOf(
                "com/tampwell/staleguard/impact/JarScanner.class" to
                    ownClassBytes("com/tampwell/staleguard/impact/JarScanner"),
            ),
        )

        val scans = assertNotNullValue(JarScanner.scan(jar))

        assertEquals("fixture.jar", scans.jarName)
        val scan = scans.classes.single()
        assertEquals("com/tampwell/staleguard/impact/JarScanner", scan.internalName)
        assertTrue("the scanner calls ClassFileApiReader.scan", scan.refs.any { it.name == "scan" })
    }

    @Test
    fun `a broken entry does not lose the jar, and a missing jar returns null`() {
        val jar = jarOf(
            mapOf(
                "broken/X.class" to byteArrayOf(1, 2, 3),
                "com/tampwell/staleguard/impact/JarScanner.class" to
                    ownClassBytes("com/tampwell/staleguard/impact/JarScanner"),
            ),
        )

        assertEquals(1, assertNotNullValue(JarScanner.scan(jar)).classes.size)
        assertNull(JarScanner.scan(temp.root.toPath().resolve("absent.jar")))
    }

    private fun <T> assertNotNullValue(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
