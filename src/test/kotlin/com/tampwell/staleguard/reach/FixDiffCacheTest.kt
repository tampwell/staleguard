package com.tampwell.staleguard.reach

import com.tampwell.staleguard.impact.MemberRef
import com.tampwell.staleguard.repository.Coordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class FixDiffCacheTest {

    private val coordinates = Coordinates("org.apache.logging.log4j", "log4j-core")

    private fun result(vararg touched: MemberRef, unreadable: List<String> = emptyList()) =
        FixDiff.Result(touched.toSet(), methodsCompared = 120, classesRemoved = 1, classesChanged = 3, unreadable = unreadable)

    @Test
    fun `a written diff reads back identically`() {
        val cache = FixDiffCache(Files.createTempDirectory("fixdiff"))
        val touched = MemberRef(
            "org/apache/logging/log4j/core/lookup/JndiLookup",
            "lookup",
            "(Lorg/apache/logging/log4j/core/LogEvent;Ljava/lang/String;)Ljava/lang/String;",
        )
        cache.write(coordinates, "2.14.1", "2.15.0", result(touched))

        val read = checkNotNull(cache.read(coordinates, "2.14.1", "2.15.0"))
        assertEquals(setOf(touched), read.touched)
        assertEquals(120, read.methodsCompared)
        assertEquals(1, read.classesRemoved)
        assertEquals(3, read.classesChanged)
    }

    @Test
    fun `an empty diff is a real answer and survives the round trip`() {
        val cache = FixDiffCache(Files.createTempDirectory("fixdiff"))
        cache.write(coordinates, "1.0", "1.1", result())

        assertEquals(emptySet<MemberRef>(), checkNotNull(cache.read(coordinates, "1.0", "1.1")).touched)
    }

    @Test
    fun `an incomplete diff is never cached`() {
        val cache = FixDiffCache(Files.createTempDirectory("fixdiff"))
        cache.write(coordinates, "1.0", "1.1", result(unreadable = listOf("a/B")))

        assertNull(cache.read(coordinates, "1.0", "1.1"))
    }

    @Test
    fun `a foreign or damaged file is treated as absent`() {
        val root = Files.createTempDirectory("fixdiff")
        val cache = FixDiffCache(root)
        cache.write(coordinates, "1.0", "1.1", result())
        val file = Files.walk(root).use { stream -> stream.filter { it.toString().endsWith(".txt") }.findFirst().get() }
        Files.writeString(file, "something else\n")

        assertNull(cache.read(coordinates, "1.0", "1.1"))
    }
}
