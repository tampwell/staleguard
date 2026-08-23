package com.tampwell.staleguard.repository

import com.tampwell.staleguard.repository.MavenMirrorSelector.CentralRoute
import com.tampwell.staleguard.repository.MavenMirrorSelector.MavenMirror
import com.tampwell.staleguard.repository.MavenMirrorSelector.Repo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MavenMirrorSelectorTest {

    private fun mirror(id: String, mirrorOf: String, url: String = "https://nexus.corp/repo", blocked: Boolean = false) =
        MavenMirror(id, mirrorOf, url, blocked)

    private val central = MavenMirrorSelector.CENTRAL

    @Test
    fun `settings xml mirrors parse including blocked flag`() {
        val mirrors = MavenMirrorSelector.parseMirrors(
            """
            <settings>
              <mirrors>
                <mirror>
                  <id>corp</id>
                  <mirrorOf>*</mirrorOf>
                  <url>https://nexus.corp.example/repository/maven-public/</url>
                </mirror>
                <mirror>
                  <id>maven-default-http-blocker</id>
                  <mirrorOf>external:http:*</mirrorOf>
                  <url>http://0.0.0.0/</url>
                  <blocked>true</blocked>
                </mirror>
                <mirror><id>broken</id></mirror>
              </mirrors>
            </settings>
            """.trimIndent(),
        )
        assertEquals(2, mirrors.size)
        assertEquals("corp", mirrors[0].id)
        assertTrue(mirrors[1].blocked)
    }

    @Test
    fun `exact mirrorOf beats a wildcard declared earlier`() {
        val selected = MavenMirrorSelector.select(
            listOf(mirror("wild", "*"), mirror("exact", "central")),
            central,
        )
        assertEquals("exact", selected?.id)
    }

    @Test
    fun `first declared wins within the pattern pass`() {
        val selected = MavenMirrorSelector.select(
            listOf(mirror("first", "external:*"), mirror("second", "*")),
            central,
        )
        assertEquals("first", selected?.id)
    }

    @Test
    fun `comma segments are not trimmed - the documented maven trap`() {
        assertNull(MavenMirrorSelector.select(listOf(mirror("m", "!repo1, *")), central))
        assertEquals("m", MavenMirrorSelector.select(listOf(mirror("m", "!repo1,*")), central)?.id)
    }

    @Test
    fun `negation excludes by literal id`() {
        assertNull(MavenMirrorSelector.select(listOf(mirror("m", "*,!central")), central))
        assertEquals("m", MavenMirrorSelector.select(listOf(mirror("m", "*,!other")), central)?.id)
    }

    @Test
    fun `external star skips localhost and file repos`() {
        val localRepo = Repo("local", "http://localhost:8081/repo")
        val fileRepo = Repo("filerepo", "file:///opt/repo")
        assertNull(MavenMirrorSelector.select(listOf(mirror("m", "external:*")), localRepo))
        assertNull(MavenMirrorSelector.select(listOf(mirror("m", "external:*")), fileRepo))
        assertEquals("m", MavenMirrorSelector.select(listOf(mirror("m", "external:*")), central)?.id)
    }

    @Test
    fun `the default http blocker never captures https central`() {
        val selected = MavenMirrorSelector.select(
            listOf(mirror("maven-default-http-blocker", "external:http:*", blocked = true)),
            central,
        )
        assertNull(selected)
        val plainHttpRepo = Repo("legacy", "http://old.example.com/m2")
        assertEquals(
            "maven-default-http-blocker",
            MavenMirrorSelector.select(
                listOf(mirror("maven-default-http-blocker", "external:http:*", blocked = true)),
                plainHttpRepo,
            )?.id,
        )
    }

    @Test
    fun `central route reflects the matched mirror`() {
        assertEquals(CentralRoute.Direct, MavenMirrorSelector.centralRoute(emptyList()))
        assertEquals(
            CentralRoute.Via("https://nexus.corp/repo", "corp"),
            MavenMirrorSelector.centralRoute(listOf(mirror("corp", "*", "https://nexus.corp/repo/"))),
        )
        assertEquals(
            CentralRoute.Blocked("corp"),
            MavenMirrorSelector.centralRoute(listOf(mirror("corp", "central", blocked = true))),
        )
    }

    @Test
    fun `mirrorOf central matches only the central id`() {
        val corpRepo = Repo("corp-releases", "https://nexus.corp.example/releases")
        assertNull(MavenMirrorSelector.select(listOf(mirror("m", "central")), corpRepo))
    }

    @Test
    fun `malformed settings xml yields no mirrors`() {
        assertTrue(MavenMirrorSelector.parseMirrors("<settings><mirrors>").isEmpty())
    }
}
