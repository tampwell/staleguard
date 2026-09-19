package com.tampwell.staleguard.reach

import com.tampwell.staleguard.impact.MemberRef
import com.tampwell.staleguard.version.MavenVersion
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FixWindowTest {

    private val releases = listOf("2.9.8", "2.9.9", "2.9.10", "2.9.10.7", "2.9.10.8-rc1", "2.9.10.8", "2.10.0")
        .map(::MavenVersion)

    @Test
    fun `the last stable release before the fix is the baseline`() {
        assertEquals("2.9.10.7", FixWindow.baseline(releases, vulnerable = "2.9.8", fixed = "2.9.10.8"))
    }

    @Test
    fun `a release candidate of the fix never becomes the baseline`() {
        // 2.9.10.8-rc1 sorts before 2.9.10.8 but may already carry the fix.
        assertEquals("2.9.10.7", FixWindow.baseline(releases, vulnerable = "2.9.9", fixed = "2.9.10.8"))
    }

    @Test
    fun `a project already at the baseline diffs its own version`() {
        assertNull(FixWindow.baseline(releases, vulnerable = "2.9.10.7", fixed = "2.9.10.8"))
    }

    @Test
    fun `an unknown release list diffs the project's own version`() {
        assertNull(FixWindow.baseline(emptyList(), vulnerable = "2.9.8", fixed = "2.9.10.8"))
    }

    private fun parserClass(vararg methods: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "lib/Parser", null, "java/lang/Object", null)
        for (name in methods) {
            writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, name, "()V", null, null).visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun `window methods present in the project's version map across unchanged`() {
        val touched = setOf(MemberRef("lib/Parser", "resolve", "()V"))
        val project = InMemoryClassSource(mapOf("lib/Parser" to parserClass("resolve", "other")))

        assertEquals(touched, FixWindow.mapOnto(touched, project))
    }

    @Test
    fun `a window method the project's version lacks falls back to the wide diff`() {
        val touched = setOf(MemberRef("lib/Parser", "resolve", "()V"), MemberRef("lib/Parser", "renamedLater", "()V"))
        val project = InMemoryClassSource(mapOf("lib/Parser" to parserClass("resolve")))

        assertNull(FixWindow.mapOnto(touched, project))
        assertNull(FixWindow.mapOnto(setOf(MemberRef("lib/Missing", "x", "()V")), project))
    }

    @Test
    fun `a fix that changed no code maps to itself`() {
        assertEquals(emptySet<MemberRef>(), FixWindow.mapOnto(emptySet(), InMemoryClassSource(emptyMap())))
    }
}
