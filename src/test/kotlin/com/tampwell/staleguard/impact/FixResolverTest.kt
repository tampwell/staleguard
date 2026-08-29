package com.tampwell.staleguard.impact

import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JarCoordinatesTest {

    @Test
    fun `gradle cache paths identify exactly`() {
        val identified = JarCoordinates.identify(
            Path.of(
                "C:\\Users\\u\\.gradle\\caches\\modules-2\\files-2.1\\com.fasterxml.jackson.core" +
                    "\\jackson-core\\2.13.0\\e957ec5442966e69cef543927bdc80e5426968bb\\jackson-core-2.13.0.jar",
            ),
        )

        assertEquals("com.fasterxml.jackson.core:jackson-core", identified?.coordinates.toString())
        assertEquals("2.13.0", identified?.version)
    }

    @Test
    fun `maven repository paths identify, classifier included`() {
        val identified = JarCoordinates.identify(
            Path.of("/home/u/.m2/repository/com/google/code/gson/gson/2.11.0/gson-2.11.0-jre.jar"),
        )

        assertEquals("com.google.code.gson:gson", identified?.coordinates.toString())
        assertEquals("2.11.0", identified?.version)
    }

    @Test
    fun `anything else identifies as nothing, never a guess`() {
        assertNull(JarCoordinates.identify(Path.of("C:\\libs\\vendored-mystery.jar")))
        assertNull(JarCoordinates.identify(Path.of("/opt/app/lib/custom-build.jar")))
    }
}

class FixResolverTest {

    private fun scansWith(vararg members: Pair<String, String>) = LinkageAudit.JarScans(
        "candidate.jar",
        members.groupBy({ it.first }, { it.second }).map { (owner, names) ->
            ClassScan(
                api = ClassApi(owner, "java/lang/Object", emptyList(), emptySet()),
                declaredAll = names.mapTo(LinkedHashSet()) { MemberKey(it, "()V") },
                refs = emptySet(),
            )
        },
    )

    private val needs = FixResolver.Needs(
        members = mapOf("lib/Api" to setOf(MemberKey("newMethod", "()V"))),
        classes = emptySet(),
    )

    private val versions = listOf("1.1", "1.2", "1.3", "1.4", "1.5", "1.6", "1.7", "1.8")

    /** Monotonic world: newMethod exists from 1.5 onward. */
    private fun monotonicProbe(version: String): LinkageAudit.JarScans =
        if (versions.indexOf(version) >= versions.indexOf("1.5")) {
            scansWith("lib/Api" to "newMethod")
        } else {
            scansWith("lib/Api" to "oldMethod")
        }

    @Test
    fun `finds the earliest satisfying version in few probes`() {
        val result = FixResolver.resolve(needs, versions, ::monotonicProbe)

        assertEquals("1.5", (result as FixResolver.Result.FixedIn).version)
        assertTrue("expected log-ish probes, got ${result.probes}", result.probes <= 5)
    }

    @Test
    fun `a member added then removed then re-added still yields a version that truly satisfies`() {
        // Present only in 1.3 and from 1.7 on: the monotonicity lie.
        val lie = setOf("1.3", "1.7", "1.8")
        val result = FixResolver.resolve(needs, versions) { v ->
            if (v in lie) scansWith("lib/Api" to "newMethod") else scansWith("lib/Api" to "other")
        }

        val fixed = result as FixResolver.Result.FixedIn
        assertTrue("suggested ${fixed.version} must actually satisfy", fixed.version in lie)
    }

    @Test
    fun `no satisfying version says so instead of guessing`() {
        val result = FixResolver.resolve(needs, versions) { scansWith("lib/Api" to "somethingElse") }

        assertTrue(result is FixResolver.Result.NoCleanVersion)
    }

    @Test
    fun `probes never exceed the budget`() {
        var calls = 0
        FixResolver.resolve(needs, (1..1000).map { "9.$it" }) { _ ->
            calls++
            scansWith("lib/Api" to "never")
        }

        assertTrue("made $calls probes", calls <= FixResolver.MAX_PROBES)
    }

    @Test
    fun `an unfetchable candidate counts as unsatisfying, not as proof`() {
        val result = FixResolver.resolve(needs, versions) { null }

        assertTrue(result is FixResolver.Result.NoCleanVersion)
    }

    @Test
    fun `evicted classes must exist in the candidate`() {
        val withClasses = FixResolver.Needs(members = emptyMap(), classes = setOf("lib/NewThing"))

        val result = FixResolver.resolve(withClasses, versions) { v ->
            if (v >= "1.6") scansWith("lib/NewThing" to "anything") else scansWith("lib/Old" to "x")
        }

        assertEquals("1.6", (result as FixResolver.Result.FixedIn).version)
    }

    @Test
    fun `members resolve through the candidate's own hierarchy`() {
        val inherited = LinkageAudit.JarScans(
            "candidate.jar",
            listOf(
                ClassScan(ClassApi("lib/Api", "lib/Base", emptyList(), emptySet()), emptySet(), emptySet()),
                ClassScan(
                    ClassApi("lib/Base", "java/lang/Object", emptyList(), emptySet()),
                    setOf(MemberKey("newMethod", "()V")),
                    emptySet(),
                ),
            ),
        )

        assertTrue(FixResolver.satisfies(needs, inherited))
    }

    @Test
    fun `a hierarchy escaping the candidate jar is not proof`() {
        val escaping = LinkageAudit.JarScans(
            "candidate.jar",
            listOf(ClassScan(ClassApi("lib/Api", "other/ElsewhereBase", emptyList(), emptySet()), emptySet(), emptySet())),
        )

        assertTrue(!FixResolver.satisfies(needs, escaping))
    }

    @Test
    fun `empty needs or empty candidates resolve to nothing without probing`() {
        var calls = 0
        val probe: (String) -> LinkageAudit.JarScans? = { calls++; null }

        FixResolver.resolve(FixResolver.Needs(emptyMap(), emptySet()), versions, probe)
        FixResolver.resolve(needs, emptyList(), probe)

        assertEquals(0, calls)
    }
}
