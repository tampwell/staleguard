package com.tampwell.staleguard.repository

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiskVersionCacheTest {

    private val dir = Files.createTempDirectory("staleguard-cache-test")
    private val cache = DiskVersionCache(dir)
    private val coords = Coordinates("com.google.guava", "guava")

    private fun entry(versions: List<String> = listOf("1.0", "2.0")) = CachedArtifact(
        groupId = coords.groupId,
        artifactId = coords.artifactId,
        versions = versions,
        etag = "\"abc\"",
        fetchedAtMillis = 1_000L,
        newestReleaseAtMillis = 500L,
        newestReleaseVersion = "2.0",
    )

    @Test
    fun `round-trips an entry`() {
        cache.write(coords, entry())
        val read = cache.read(coords)
        assertEquals(entry(), read)
    }

    @Test
    fun `missing entry reads as null`() {
        assertNull(cache.read(Coordinates("no.such", "artifact")))
    }

    @Test
    fun `overwrite replaces previous entry`() {
        cache.write(coords, entry(listOf("1.0")))
        cache.write(coords, entry(listOf("1.0", "1.1")))
        assertEquals(listOf("1.0", "1.1"), cache.read(coords)?.versions)
    }

    @Test
    fun `corrupt file is treated as absent and removed`() {
        cache.write(coords, entry())
        val file = Files.list(dir).filter { it.toString().endsWith(".json") }.findFirst().get()
        Files.writeString(file, "{ not json !!")
        assertNull(cache.read(coords))
        assertNull(cache.read(coords)) // still gone, no crash on second read
    }

    @Test
    fun `unknown schema version is discarded`() {
        cache.write(coords, entry().copy(schema = 999))
        assertNull(cache.read(coords))
    }

    @Test
    fun `coordinates with unusual characters produce safe distinct files`() {
        val weird1 = Coordinates("com.example", "art:with*chars")
        val weird2 = Coordinates("com.example", "art_with_chars")
        cache.write(weird1, entry())
        cache.write(weird2, entry(listOf("9.9")))
        assertEquals(listOf("1.0", "2.0"), cache.read(weird1)?.versions)
        assertEquals(listOf("9.9"), cache.read(weird2)?.versions)
    }
}
