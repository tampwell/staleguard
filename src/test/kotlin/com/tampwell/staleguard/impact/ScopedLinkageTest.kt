package com.tampwell.staleguard.impact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopedLinkageTest {

    private fun scan(
        name: String,
        superName: String? = "java/lang/Object",
        declared: List<Pair<String, String>> = emptyList(),
        refs: List<Triple<String, String, String>> = emptyList(),
    ) = ClassScan(
        api = ClassApi(name, superName, emptyList(), declared.map { MemberKey(it.first, it.second) }.toSet()),
        declaredAll = declared.mapTo(LinkedHashSet()) { MemberKey(it.first, it.second) },
        refs = refs.mapTo(LinkedHashSet()) { MemberRef(it.first, it.second, it.third) },
    )

    private fun jar(name: String, vararg classes: ClassScan) = LinkageAudit.JarScans(name, classes.toList())

    private val noJdk: (String, String) -> Boolean = { _, _ -> false }

    private val caller = jar("caller.jar", scan("app/Main", refs = listOf(Triple("lib/Missing", "run", "()V"))))
    private val libCore = jar("lib-core.jar", scan("lib/Other", declared = listOf("other" to "()V")))
    private val libFull = jar("lib.jar", scan("lib/Missing", declared = listOf("run" to "()V")))

    @Test
    fun `a union classpath hides a missing class that a module scope catches`() {
        // Module A's real classpath lacks lib/Missing though its package is
        // there; module B happens to have it. The union resolves A's call
        // against B's jar and stays silent — the exact false all-clear the
        // per-scope audit exists to end.
        val union = LinkageAudit.run(listOf(libFull, caller, libCore), noJdk)
        assertTrue(union.clean)

        val merged = ScopedLinkage.run(
            listOf(
                ScopedLinkage.Scope("moduleA", listOf(caller, libCore)),
                ScopedLinkage.Scope("moduleB", listOf(libFull)),
            ),
            noJdk,
        )

        val evicted = merged.report.evictedClasses.single()
        assertEquals("lib/Missing", evicted.owner)
        assertEquals(listOf("moduleA"), merged.modulesByFinding[LinkageDelta.keyOf(evicted)])
    }

    @Test
    fun `the same finding in two scopes merges to one, attributed to both`() {
        val merged = ScopedLinkage.run(
            listOf(
                ScopedLinkage.Scope("app", listOf(caller, libCore)),
                ScopedLinkage.Scope("web", listOf(caller, libCore, jar("extra.jar"))),
            ),
            noJdk,
        )

        val evicted = merged.report.evictedClasses.single()
        assertEquals(listOf("app", "web"), merged.modulesByFinding[LinkageDelta.keyOf(evicted)])
    }

    @Test
    fun `a single scope reports exactly what the plain audit reports`() {
        val jars = listOf(caller, libCore)
        val plain = LinkageAudit.run(jars, noJdk)

        val merged = ScopedLinkage.run(listOf(ScopedLinkage.Scope("only", jars)), noJdk)

        assertEquals(1, merged.moduleCount)
        assertEquals(plain.brokenMembers, merged.report.brokenMembers)
        assertEquals(plain.evictedClasses, merged.report.evictedClasses)
        assertEquals(plain.refCount, merged.report.refCount)
        assertEquals(plain.jarCount, merged.report.jarCount)
    }

    @Test
    fun `scopes sharing a jar set are audited once and both attributed`() {
        var platformLookups = 0
        val counting: (String, String) -> Boolean = { _, _ ->
            platformLookups++ // fires once per audit of this jar set, via the Object supertype walk
            false
        }
        val jars = listOf(
            jar("a.jar", scan("a/Caller", refs = listOf(Triple("b/Api", "gone", "()V")))),
            jar("b.jar", scan("b/Api", declared = listOf("kept" to "()V"))),
        )

        val merged = ScopedLinkage.run(
            listOf(ScopedLinkage.Scope("first", jars), ScopedLinkage.Scope("second", jars)),
            counting,
        )

        val broken = merged.report.brokenMembers.single()
        assertEquals(listOf("first", "second"), merged.modulesByFinding[LinkageDelta.keyOf(broken)])
        assertEquals(1, platformLookups) // the shared jar set was audited once, not once per scope
        assertEquals(2, merged.moduleCount)
    }

    @Test
    fun `corpus counts describe each jar once, however many scopes share it`() {
        val merged = ScopedLinkage.run(
            listOf(
                ScopedLinkage.Scope("app", listOf(caller, libCore)),
                ScopedLinkage.Scope("web", listOf(caller, libCore)),
                ScopedLinkage.Scope("api", listOf(libCore)),
            ),
            noJdk,
        )

        assertEquals(2, merged.report.jarCount)
        assertEquals(2, merged.report.classCount)
        assertEquals(1, merged.report.refCount)
    }

    @Test
    fun `shadowed classes merge across scopes with module attribution`() {
        fun copy(members: List<String>) = ClassScan(
            api = ClassApi("dup/Thing", "java/lang/Object", emptyList(), members.map { MemberKey(it, "()V") }.toSet()),
            declaredAll = members.mapTo(LinkedHashSet()) { MemberKey(it, "()V") },
            refs = emptySet(),
        )
        val winner = jar("old.jar", copy(listOf("run")))
        val loser = jar("new.jar", copy(listOf("run", "runFaster")))

        val merged = ScopedLinkage.run(
            listOf(
                ScopedLinkage.Scope("app", listOf(winner, loser)),
                ScopedLinkage.Scope("web", listOf(winner, loser)),
                ScopedLinkage.Scope("api", listOf(winner)),
            ),
            noJdk,
        )

        val shadow = merged.report.shadowedGroups.single()
        assertEquals("old.jar", shadow.winnerJar)
        assertEquals(listOf("app", "web"), merged.modulesByFinding[LinkageDelta.keyOf(shadow)])
        assertTrue(merged.report.clean) // latent, not broken calls
    }

    @Test
    fun `progress reports scopes finished out of the total, reaching the end`() {
        val ticks = mutableListOf<Pair<Int, Int>>()

        ScopedLinkage.run(
            listOf(
                ScopedLinkage.Scope("app", listOf(caller, libCore)),
                ScopedLinkage.Scope("web", listOf(caller, libCore)),
                ScopedLinkage.Scope("api", listOf(libFull)),
            ),
            noJdk,
        ) { finished, total -> ticks += finished to total }

        assertEquals(3, ticks.last().first)
        assertTrue(ticks.all { it.second == 3 })
    }
}
