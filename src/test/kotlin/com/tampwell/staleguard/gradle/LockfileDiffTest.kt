package com.tampwell.staleguard.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LockfileDiffTest {

    private fun locked(coordinate: String): Lockfile.Locked {
        val (group, name, version) = coordinate.split(':')
        return Lockfile.Locked(group, name, version, listOf("compileClasspath"))
    }

    @Test
    fun `a version movement is a change not an add plus remove`() {
        val delta = LockfileDiff.diff(listOf(locked("g:a:1.0")), listOf(locked("g:a:1.1")))

        assertEquals(listOf(LockfileDiff.Movement("g", "a", "1.0", "1.1")), delta.changed)
        assertTrue(delta.added.isEmpty())
        assertTrue(delta.removed.isEmpty())
    }

    @Test
    fun `arrivals and departures are reported as such`() {
        val delta = LockfileDiff.diff(
            listOf(locked("g:leaving:1.0"), locked("g:staying:2.0")),
            listOf(locked("g:staying:2.0"), locked("g:arriving:3.0")),
        )

        assertEquals(listOf("arriving"), delta.added.map { it.name })
        assertEquals(listOf("leaving"), delta.removed.map { it.name })
        assertTrue(delta.changed.isEmpty())
    }

    @Test
    fun `identical states produce an empty delta`() {
        val state = listOf(locked("g:a:1.0"), locked("g:b:2.0"))

        assertTrue(LockfileDiff.diff(state, state).isEmpty)
    }

    @Test
    fun `duplicate coordinates dedupe to the first occurrence`() {
        val before = listOf(locked("g:a:1.0"), locked("g:a:9.9"))
        val after = listOf(locked("g:a:1.1"), locked("g:a:8.8"))

        val delta = LockfileDiff.diff(before, after)

        assertEquals(listOf(LockfileDiff.Movement("g", "a", "1.0", "1.1")), delta.changed)
    }
}
