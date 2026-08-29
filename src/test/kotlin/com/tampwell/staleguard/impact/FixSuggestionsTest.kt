package com.tampwell.staleguard.impact

import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.version.MavenVersion
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixSuggestionsTest {

    private val jacksonCore = Coordinates("com.fasterxml.jackson.core", "jackson-core")

    private val broken = LinkageAudit.BrokenRef(
        "jackson-databind-2.19.0.jar",
        MemberRef("com/fasterxml/jackson/core/JsonParser", "streamReadConstraints", "()V"),
        "jackson-core-2.13.0.jar",
    )
    private val evicted = LinkageAudit.EvictedClassRefs(
        "jackson-databind-2.19.0.jar",
        "com/fasterxml/jackson/core/StreamReadConstraints",
        16,
    )

    private fun report(
        brokenMembers: List<LinkageAudit.BrokenRef> = listOf(broken),
        evictedClasses: List<LinkageAudit.EvictedClassRefs> = listOf(evicted),
    ) = LinkageAudit.Report(3, 900, 22902, brokenMembers, evictedClasses)

    private fun candidateScans(vararg versions: String): (Coordinates, String) -> LinkageAudit.JarScans? =
        { _, version ->
            if (version in versions) {
                LinkageAudit.JarScans(
                    "candidate.jar",
                    listOf(
                        ClassScan(
                            ClassApi("com/fasterxml/jackson/core/JsonParser", "java/lang/Object", emptyList(), emptySet()),
                            setOf(MemberKey("streamReadConstraints", "()V")),
                            emptySet(),
                        ),
                        ClassScan(
                            ClassApi("com/fasterxml/jackson/core/StreamReadConstraints", "java/lang/Object", emptyList(), emptySet()),
                            emptySet(),
                            emptySet(),
                        ),
                    ),
                )
            } else {
                LinkageAudit.JarScans("candidate.jar", emptyList())
            }
        }

    private fun sources(
        versions: List<String>? = listOf("2.14.0", "2.15.0", "2.16.0", "2.17.0"),
        allowed: (Coordinates, MavenVersion?, MavenVersion) -> Boolean = { _, _, _ -> true },
        probe: (Coordinates, String) -> LinkageAudit.JarScans? = candidateScans("2.15.0", "2.16.0", "2.17.0"),
    ) = FixSuggestions.Sources(
        identify = { jarName ->
            if (jarName == "jackson-core-2.13.0.jar") {
                JarCoordinates.Identified(jacksonCore, "2.13.0")
            } else {
                null
            }
        },
        packageOwner = { pkg -> if (pkg == "com/fasterxml/jackson/core") "jackson-core-2.13.0.jar" else null },
        versionsFor = { coords -> if (coords == jacksonCore) versions else null },
        versionAllowed = allowed,
        probe = probe,
    )

    @Test
    fun `the classic jackson conflict resolves to the earliest fixing version`() {
        val suggestions = FixSuggestions.compute(report(), sources())

        assertEquals(
            FixSuggestions.Suggestion.FixedIn("2.15.0"),
            suggestions["jackson-core-2.13.0.jar"],
        )
    }

    @Test
    fun `broken members and evicted classes both land on the jar whose version must move`() {
        // Only the evicted class this time: attribution goes through packageOwner.
        val suggestions = FixSuggestions.compute(report(brokenMembers = emptyList()), sources())

        assertTrue("jackson-core-2.13.0.jar" in suggestions)
    }

    @Test
    fun `an unidentifiable jar gets no suggestion, never a guess`() {
        val vendored = broken.copy(ownerJar = "vendored-mystery.jar")

        val suggestions = FixSuggestions.compute(
            report(brokenMembers = listOf(vendored), evictedClasses = emptyList()),
            sources(),
        )

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `no cached version list means no suggestion this run`() {
        assertTrue(FixSuggestions.compute(report(), sources(versions = null)).isEmpty())
    }

    @Test
    fun `pinned-away versions are never suggested`() {
        // Policy caps jackson-core below 2.15: the fixing versions are all
        // forbidden, so the honest answer is no clean version, not a
        // suggestion that fights the team's own pin file.
        val suggestions = FixSuggestions.compute(
            report(),
            sources(allowed = { _, _, candidate -> candidate < MavenVersion("2.15.0") }),
        )

        assertEquals(FixSuggestions.Suggestion.NoCleanVersion, suggestions["jackson-core-2.13.0.jar"])
    }

    @Test
    fun `only versions newer than the resolved one are candidates`() {
        var probed = mutableListOf<String>()
        FixSuggestions.compute(
            report(),
            sources(
                versions = listOf("2.12.0", "2.13.0", "2.15.0", "2.16.0"),
                probe = { c, v -> probed.add(v); candidateScans("2.15.0", "2.16.0")(c, v) },
            ),
        )

        assertTrue("probed $probed", probed.none { MavenVersion(it) <= MavenVersion("2.13.0") })
    }

    @Test
    fun `nothing fixes it, the answer says so`() {
        val suggestions = FixSuggestions.compute(
            report(),
            sources(probe = { _, _ -> LinkageAudit.JarScans("candidate.jar", emptyList()) }),
        )

        assertEquals(FixSuggestions.Suggestion.NoCleanVersion, suggestions["jackson-core-2.13.0.jar"])
    }

    @Test
    fun `identify works end to end with the real path mapper`() {
        val identified = JarCoordinates.identify(
            Path.of(
                "C:\\Users\\u\\.gradle\\caches\\modules-2\\files-2.1\\com.fasterxml.jackson.core" +
                    "\\jackson-core\\2.13.0\\abcdef0123456789abcdef\\jackson-core-2.13.0.jar",
            ),
        )

        assertEquals(jacksonCore, identified?.coordinates)
    }
}
