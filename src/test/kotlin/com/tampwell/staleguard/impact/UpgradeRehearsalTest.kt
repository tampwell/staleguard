package com.tampwell.staleguard.impact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpgradeRehearsalTest {

    private fun scan(
        name: String,
        declared: List<String> = emptyList(),
        refs: List<Triple<String, String, String>> = emptyList(),
    ) = ClassScan(
        api = ClassApi(name, "java/lang/Object", emptyList(), declared.map { MemberKey(it, "()V") }.toSet()),
        declaredAll = declared.mapTo(LinkedHashSet()) { MemberKey(it, "()V") },
        refs = refs.mapTo(LinkedHashSet()) { MemberRef(it.first, it.second, it.third) },
    )

    private fun jar(name: String, vararg classes: ClassScan) = LinkageAudit.JarScans(name, classes.toList())

    private val noJdk: (String, String) -> Boolean = { _, _ -> false }

    private val caller = jar("caller.jar", scan("app/Main", refs = listOf(Triple("lib/Util", "gone", "()V"))))
    private val oldLib = jar("lib-1.0.jar", scan("lib/Util", declared = listOf("kept")))

    @Test
    fun `an upgrade that restores the missing member fixes the known problem`() {
        val newLib = jar("lib-2.0.jar", scan("lib/Util", declared = listOf("kept", "gone")))

        val verdict = UpgradeRehearsal.rehearse(
            listOf(ScopedLinkage.Scope("app", listOf(caller, oldLib))),
            currentJarName = "lib-1.0.jar",
            replacement = newLib,
            platformMembers = noJdk,
        )

        assertEquals(1, verdict.fixedCount)
        assertEquals("gone", verdict.fixedBroken.single().ref.name)
        assertTrue(verdict.introduced.isNews.not())
    }

    @Test
    fun `an upgrade that drops a member somebody calls introduces a new problem`() {
        val happyCaller = jar("caller.jar", scan("app/Main", refs = listOf(Triple("lib/Util", "kept", "()V"))))
        val newLib = jar("lib-2.0.jar", scan("lib/Util", declared = listOf("renamed")))

        val verdict = UpgradeRehearsal.rehearse(
            listOf(ScopedLinkage.Scope("app", listOf(happyCaller, jar("lib-1.0.jar", scan("lib/Util", declared = listOf("kept")))))),
            currentJarName = "lib-1.0.jar",
            replacement = newLib,
            platformMembers = noJdk,
        )

        assertEquals(0, verdict.fixedCount)
        assertEquals("kept", verdict.introduced.newBroken.single().ref.name)
    }

    @Test
    fun `problems unrelated to the upgraded jar are neither fixed nor introduced`() {
        val unrelatedBreak = jar("other-caller.jar", scan("x/Caller", refs = listOf(Triple("y/Api", "missing", "()V"))))
        val unrelatedLib = jar("y.jar", scan("y/Api", declared = listOf("present")))
        val newLib = jar("lib-2.0.jar", scan("lib/Util", declared = listOf("kept", "gone")))

        val verdict = UpgradeRehearsal.rehearse(
            listOf(ScopedLinkage.Scope("app", listOf(caller, oldLib, unrelatedBreak, unrelatedLib))),
            currentJarName = "lib-1.0.jar",
            replacement = newLib,
            platformMembers = noJdk,
        )

        // The pre-existing unrelated break survives the upgrade untouched:
        // only the fixed call to lib/Util.gone counts as fixed.
        assertEquals(1, verdict.fixedCount)
        assertTrue(verdict.introduced.isNews.not())
    }

    @Test
    fun `display lines name the caller and the member`() {
        val newLib = jar("lib-2.0.jar", scan("lib/Util", declared = listOf("kept", "gone")))

        val verdict = UpgradeRehearsal.rehearse(
            listOf(ScopedLinkage.Scope("app", listOf(caller, oldLib))),
            currentJarName = "lib-1.0.jar",
            replacement = newLib,
            platformMembers = noJdk,
        )

        val line = verdict.fixedLines.single()
        assertTrue("caller.jar" in line)
        assertTrue("gone" in line)
    }
}
