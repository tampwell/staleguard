package com.tampwell.staleguard.impact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowAuditTest {

    private fun scan(
        name: String,
        superName: String? = "java/lang/Object",
        interfaces: List<String> = emptyList(),
        members: List<String> = emptyList(),
        synthetics: List<String> = emptyList(),
    ): ClassScan {
        val api = members.map { MemberKey(it, "()V") }.toSet()
        return ClassScan(
            api = ClassApi(name, superName, interfaces, api),
            declaredAll = api + synthetics.map { MemberKey(it, "()V") },
            refs = emptySet(),
        )
    }

    private fun jar(name: String, vararg classes: ClassScan) = LinkageAudit.JarScans(name, classes.toList())

    @Test
    fun `identical copies are silent, even when synthetics differ`() {
        // Recompilation changes lambda synthetics without changing what a
        // caller can link against; bytes differing must not be an accusation.
        val groups = ShadowAudit.run(
            listOf(
                jar("a.jar", scan("lib/Thing", members = listOf("run"), synthetics = listOf("lambda\$run\$0"))),
                jar("b.jar", scan("lib/Thing", members = listOf("run"), synthetics = listOf("lambda\$run\$1"))),
            ),
        )

        assertTrue(groups.isEmpty())
    }

    @Test
    fun `a copy declaring members the winner lacks is a conflict, and the first jar wins`() {
        val groups = ShadowAudit.run(
            listOf(
                jar("old.jar", scan("lib/Thing", members = listOf("run"))),
                jar("new.jar", scan("lib/Thing", members = listOf("run", "runFaster"))),
            ),
        )

        val group = groups.single()
        assertEquals("old.jar", group.winnerJar)
        assertEquals(listOf("new.jar"), group.shadowedJars)
        assertEquals(listOf("lib.Thing"), group.examples)
    }

    @Test
    fun `a winner covering everything the other copy declares is silent`() {
        // The winner can satisfy every call compiled against the loser.
        val groups = ShadowAudit.run(
            listOf(
                jar("new.jar", scan("lib/Thing", members = listOf("run", "runFaster"))),
                jar("old.jar", scan("lib/Thing", members = listOf("run"))),
            ),
        )

        assertTrue(groups.isEmpty())
    }

    @Test
    fun `a differing supertype is a conflict even with identical members`() {
        val groups = ShadowAudit.run(
            listOf(
                jar("a.jar", scan("lib/Thing", superName = "lib/BaseA", members = listOf("run"))),
                jar("b.jar", scan("lib/Thing", superName = "lib/BaseB", members = listOf("run"))),
            ),
        )

        assertEquals(1, groups.size)
    }

    @Test
    fun `many conflicted classes between the same jars are one group, examples capped`() {
        val winners = (1..5).map { scan("lib/C$it", members = listOf("a")) }
        val losers = (1..5).map { scan("lib/C$it", members = listOf("a", "b")) }

        val groups = ShadowAudit.run(
            listOf(jar("a.jar", *winners.toTypedArray()), jar("b.jar", *losers.toTypedArray())),
        )

        val group = groups.single()
        assertEquals(5, group.classCount)
        assertEquals(3, group.examples.size)
    }

    @Test
    fun `two conflicting copies in different jars both appear as shadowed`() {
        val groups = ShadowAudit.run(
            listOf(
                jar("winner.jar", scan("lib/Thing", members = listOf("a"))),
                jar("z.jar", scan("lib/Thing", members = listOf("a", "z"))),
                jar("b.jar", scan("lib/Thing", members = listOf("a", "b"))),
            ),
        )

        assertEquals(listOf("b.jar", "z.jar"), groups.single().shadowedJars)
    }
}
