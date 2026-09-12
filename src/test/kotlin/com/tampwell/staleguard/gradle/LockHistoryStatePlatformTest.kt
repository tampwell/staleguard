package com.tampwell.staleguard.gradle

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Baseline-then-delta for relocks: first sighting is silent, a change
 * becomes the retained story, and the story survives serialization.
 */
class LockHistoryStatePlatformTest : BasePlatformTestCase() {

    private fun entry(text: String): LockfileScan.Entry {
        val psi = myFixture.addFileToProject("gradle.lockfile", text)
        return LockfileScan.Entry(
            psi.virtualFile,
            checkNotNull(Lockfile.locate(psi.virtualFile.path.replace('\\', '/'))),
            Lockfile.parseLines(text),
        )
    }

    fun `test first sighting is a silent baseline and a change becomes the story`() {
        val state = LockHistoryState()
        val before = entry("g:a:1.0=compileClasspath\n")

        state.update(listOf(before), nowMillis = 1_000)
        assertTrue(state.lastRelocks.isEmpty())

        val after = LockfileScan.Entry(
            before.file, before.located,
            Lockfile.parseLines("g:a:1.1=compileClasspath\ng:new:2.0=compileClasspath\n"),
        )
        state.update(listOf(after), nowMillis = 2_000)

        val relock = state.lastRelocks.single()
        assertEquals(listOf(LockfileDiff.Movement("g", "a", "1.0", "1.1")), relock.delta.changed)
        assertEquals(listOf("new"), relock.delta.added.map { it.name })

        // An unchanged rescan keeps the story instead of erasing it.
        state.update(listOf(after), nowMillis = 3_000)
        assertEquals(1, state.lastRelocks.size)
        assertEquals(2_000, state.lastRelocks.single().atMillis)
    }

    fun `test the story survives a serialization round trip`() {
        val state = LockHistoryState()
        val before = entry("g:a:1.0=compileClasspath\n")
        state.update(listOf(before), nowMillis = 1_000)
        state.update(
            listOf(LockfileScan.Entry(before.file, before.located, Lockfile.parseLines("g:a:1.1=compileClasspath\n"))),
            nowMillis = 2_000,
        )

        val reloaded = LockHistoryState()
        reloaded.loadState(state.state)

        val relock = reloaded.lastRelocks.single()
        assertEquals(listOf(LockfileDiff.Movement("g", "a", "1.0", "1.1")), relock.delta.changed)

        // And the reloaded baseline still guards against re-reporting.
        reloaded.update(
            listOf(LockfileScan.Entry(before.file, before.located, Lockfile.parseLines("g:a:1.1=compileClasspath\n"))),
            nowMillis = 3_000,
        )
        assertEquals(2_000, reloaded.lastRelocks.single().atMillis)
    }
}
