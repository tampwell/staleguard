package com.tampwell.staleguard.impact

import com.tampwell.staleguard.repository.Coordinates
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JarApiReaderTest {

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
    fun `reads every public class in a jar`() {
        val jar = jarOf(
            mapOf(
                "com/tampwell/staleguard/impact/MemberKey.class" to
                    ownClassBytes("com/tampwell/staleguard/impact/MemberKey"),
                "com/tampwell/staleguard/impact/JvmDescriptors.class" to
                    ownClassBytes("com/tampwell/staleguard/impact/JvmDescriptors"),
            ),
        )

        val surface = checkNotNull(JarApiReader.read(jar))

        assertEquals(
            setOf("com/tampwell/staleguard/impact/MemberKey", "com/tampwell/staleguard/impact/JvmDescriptors"),
            surface.classes.keys,
        )
        assertTrue(surface.memberCount > 0)
    }

    @Test
    fun `META-INF is skipped, so multi-release jars compare at their base version`() {
        val bytes = ownClassBytes("com/tampwell/staleguard/impact/MemberKey")
        val jar = jarOf(
            mapOf(
                "com/tampwell/staleguard/impact/MemberKey.class" to bytes,
                "META-INF/versions/21/com/tampwell/staleguard/impact/MemberKey.class" to bytes,
                "META-INF/MANIFEST.MF" to "Multi-Release: true\n".toByteArray(),
            ),
        )

        assertEquals(1, checkNotNull(JarApiReader.read(jar)).classes.size)
    }

    @Test
    fun `one broken entry does not lose the rest of the jar`() {
        val jar = jarOf(
            mapOf(
                "broken/Thing.class" to byteArrayOf(9, 9, 9, 9),
                "com/tampwell/staleguard/impact/MemberKey.class" to
                    ownClassBytes("com/tampwell/staleguard/impact/MemberKey"),
            ),
        )

        val surface = checkNotNull(JarApiReader.read(jar))

        assertEquals(setOf("com/tampwell/staleguard/impact/MemberKey"), surface.classes.keys)
    }

    @Test
    fun `cancellation returns null, never an empty surface a diff would read as no removals`() {
        val jar = jarOf(
            mapOf(
                "com/tampwell/staleguard/impact/MemberKey.class" to
                    ownClassBytes("com/tampwell/staleguard/impact/MemberKey"),
            ),
        )

        assertNull(JarApiReader.read(jar) { true })
    }

    @Test
    fun `the classpath lookup finds a supertype in another jar`() {
        val jar = jarOf(
            mapOf(
                "com/tampwell/staleguard/impact/MemberKey.class" to
                    ownClassBytes("com/tampwell/staleguard/impact/MemberKey"),
            ),
        )

        ClasspathClassLookup(listOf(jar)).use { lookup ->
            assertEquals(
                "com/tampwell/staleguard/impact/MemberKey",
                lookup.find("com/tampwell/staleguard/impact/MemberKey")?.internalName,
            )
            assertNull(lookup.find("nothing/Here"))
            // The negative answer must be remembered, not re-derived on every
            // one of the thousands of lookups a diff performs.
            assertNull(lookup.find("nothing/Here"))
        }
    }
}

class MavenArtifactUrlsTest {

    @Test
    fun `the jar url is the pom url with the packaging swapped`() {
        assertEquals(
            "https://repo1.maven.org/maven2/com/foo/bar/1.2.3/bar-1.2.3.jar",
            MavenArtifactUrls.siblingWithExtension(
                "https://repo1.maven.org/maven2/com/foo/bar/1.2.3/bar-1.2.3.pom",
                "jar",
            ),
        )
    }

    @Test
    fun `routing is inherited, so a mirrored base survives the swap`() {
        assertEquals(
            "https://nexus.internal/repository/central/com/foo/bar/1.2.3/bar-1.2.3.aar",
            MavenArtifactUrls.siblingWithExtension(
                "https://nexus.internal/repository/central/com/foo/bar/1.2.3/bar-1.2.3.pom",
                "aar",
            ),
        )
    }
}

class ProjectClasspathTest {

    @Test
    fun `the maven layout filename matches first`() {
        val jars = listOf(Path.of("/cache/other-1.0.jar"), Path.of("/cache/gson-2.11.0.jar"))

        assertEquals(Path.of("/cache/gson-2.11.0.jar"), ProjectClasspath.findArtifactJar(jars, "gson", "2.11.0"))
    }

    @Test
    fun `a gradle cache path matches on its artifact and version segments`() {
        val jars = listOf(
            Path.of("/caches/modules-2/files-2.1/com.google.code.gson/gson/2.11.0/abc123/gson-2.11.0-jre.jar"),
        )

        assertEquals(jars[0], ProjectClasspath.findArtifactJar(jars, "gson", "2.11.0"))
    }

    @Test
    fun `a different version is not matched`() {
        val jars = listOf(Path.of("/cache/gson-2.8.9.jar"))

        assertNull(ProjectClasspath.findArtifactJar(jars, "gson", "2.11.0"))
    }
}

class RemovedMembersCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val coordinates = Coordinates("com.fasterxml.jackson.core", "jackson-databind")

    @Test
    fun `a written diff reads back exactly`() {
        val cache = RemovedMembersCache(temp.newFolder().toPath())
        val removed = setOf(
            MemberRef("a/B", "gone", "()V"),
            MemberRef("a/B\$Inner", "<init>", "(Ljava/lang/String;)V"),
        )

        cache.write(coordinates, "2.13.0", "2.19.0", removed)

        assertEquals(removed, cache.read(coordinates, "2.13.0", "2.19.0"))
    }

    @Test
    fun `a different version pair is a different entry`() {
        val cache = RemovedMembersCache(temp.newFolder().toPath())
        cache.write(coordinates, "2.13.0", "2.19.0", setOf(MemberRef("a/B", "gone", "()V")))

        assertNull(cache.read(coordinates, "2.13.0", "2.18.0"))
    }

    @Test
    fun `an empty diff is cached as an answer, not as a miss`() {
        val cache = RemovedMembersCache(temp.newFolder().toPath())
        cache.write(coordinates, "2.13.0", "2.13.1", emptySet())

        assertEquals(emptySet<MemberRef>(), cache.read(coordinates, "2.13.0", "2.13.1"))
    }

    @Test
    fun `a corrupt entry is discarded rather than thrown`() {
        val directory = temp.newFolder().toPath()
        val cache = RemovedMembersCache(directory)
        cache.write(coordinates, "1.0", "2.0", setOf(MemberRef("a/B", "x", "()V")))
        val file = Files.list(directory).use { it.toList().single() }
        Files.writeString(file, "{ not json")

        assertNull(cache.read(coordinates, "1.0", "2.0"))
        assertFalse("a corrupt cache file must be deleted", Files.exists(file))
    }

    @Test
    fun `version strings that look like paths cannot escape the cache directory`() {
        val directory = temp.newFolder().toPath()
        val cache = RemovedMembersCache(directory)

        cache.write(Coordinates("g", "a"), "../../etc", "1.0", setOf(MemberRef("a/B", "x", "()V")))

        val written = Files.list(directory).use { it.toList() }
        assertEquals(1, written.size)
        assertEquals(directory, written.single().parent)
    }
}
