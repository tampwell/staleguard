package com.tampwell.staleguard.impact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkageAuditTest {

    private fun scan(
        name: String,
        superName: String? = "java/lang/Object",
        interfaces: List<String> = emptyList(),
        declared: List<Pair<String, String>> = emptyList(),
        refs: List<Triple<String, String, String>> = emptyList(),
    ) = ClassScan(
        api = ClassApi(name, superName, interfaces, declared.map { MemberKey(it.first, it.second) }.toSet()),
        declaredAll = declared.mapTo(LinkedHashSet()) { MemberKey(it.first, it.second) },
        refs = refs.mapTo(LinkedHashSet()) { MemberRef(it.first, it.second, it.third) },
    )

    private val noJdk: (String, String) -> Boolean = { _, _ -> false }
    private val objectOnly: (String, String) -> Boolean = { type, member ->
        type == "java/lang/Object" && member in setOf("toString", "hashCode", "equals", "<init>", "getClass")
    }

    @Test
    fun `a consistent classpath is clean`() {
        val app = scan("app/Main", refs = listOf(Triple("lib/Util", "help", "()V")))
        val lib = scan("lib/Util", declared = listOf("help" to "()V"))

        val report = LinkageAudit.run(
            listOf(LinkageAudit.JarScans("app.jar", listOf(app)), LinkageAudit.JarScans("lib.jar", listOf(lib))),
            noJdk,
        )

        assertTrue(report.clean)
        assertEquals(1, report.refCount)
    }

    @Test
    fun `a call to a member the resolved version lacks is broken, and names both jars`() {
        val app = scan("app/Main", refs = listOf(Triple("lib/Util", "gone", "()V")))
        val lib = scan("lib/Util", declared = listOf("kept" to "()V"))

        val report = LinkageAudit.run(
            listOf(LinkageAudit.JarScans("app.jar", listOf(app)), LinkageAudit.JarScans("old-lib.jar", listOf(lib))),
            noJdk,
        )

        assertEquals(1, report.brokenMembers.size)
        val broken = report.brokenMembers.single()
        assertEquals("app.jar", broken.fromJar)
        assertEquals("old-lib.jar", broken.ownerJar)
        assertEquals("gone", broken.ref.name)
    }

    @Test
    fun `a member found up the hierarchy is not broken`() {
        val app = scan("app/Main", refs = listOf(Triple("lib/Special", "help", "()V")))
        val special = scan("lib/Special", superName = "lib/Base")
        val base = scan("lib/Base", declared = listOf("help" to "()V"))

        val report = LinkageAudit.run(
            listOf(
                LinkageAudit.JarScans("app.jar", listOf(app)),
                LinkageAudit.JarScans("lib.jar", listOf(special, base)),
            ),
            noJdk,
        )

        assertTrue(report.clean)
    }

    @Test
    fun `a private or bridge member resolves, because linkage is not the API view`() {
        val declared = ClassScan(
            api = ClassApi("lib/Util", "java/lang/Object", emptyList(), emptySet()),
            declaredAll = setOf(MemberKey("internal", "()V")),
            refs = emptySet(),
        )
        val app = scan("app/Main", refs = listOf(Triple("lib/Util", "internal", "()V")))

        val report = LinkageAudit.run(
            listOf(LinkageAudit.JarScans("app.jar", listOf(app)), LinkageAudit.JarScans("lib.jar", listOf(declared))),
            noJdk,
        )

        assertTrue(report.clean)
    }

    @Test
    fun `a missing class with its package partially present is an eviction`() {
        val app = scan(
            "app/Main",
            refs = listOf(
                Triple("lib/NewThing", "make", "()V"),
                Triple("lib/NewThing", "run", "()V"),
            ),
        )
        val lib = scan("lib/OldThing", declared = listOf("x" to "()V"))

        val report = LinkageAudit.run(
            listOf(LinkageAudit.JarScans("app.jar", listOf(app)), LinkageAudit.JarScans("lib.jar", listOf(lib))),
            noJdk,
        )

        assertEquals(1, report.evictedClasses.size)
        val evicted = report.evictedClasses.single()
        assertEquals("lib/NewThing", evicted.owner)
        assertEquals(2, evicted.refCount)
        assertTrue(report.brokenMembers.isEmpty())
    }

    @Test
    fun `a wholly absent package is an optional dependency and stays silent`() {
        val app = scan("app/Main", refs = listOf(Triple("optional/Extra", "boost", "()V")))

        val report = LinkageAudit.run(listOf(LinkageAudit.JarScans("app.jar", listOf(app))), noJdk)

        assertTrue(report.clean)
    }

    @Test
    fun `jdk members resolve through the platform lookup, including up the jdk chain`() {
        val app = scan("app/Main", refs = listOf(Triple("java/util/ArrayList", "toString", "()Ljava/lang/String;")))
        val jdk: (String, String) -> Boolean = { type, member ->
            type == "java/util/ArrayList" && member == "toString"
        }

        assertTrue(LinkageAudit.run(listOf(LinkageAudit.JarScans("app.jar", listOf(app))), jdk).clean)
    }

    @Test
    fun `a hierarchy escaping into an unknown non-jdk supertype is never an accusation`() {
        val app = scan("app/Main", refs = listOf(Triple("lib/Child", "somewhere", "()V")))
        val child = scan("lib/Child", superName = "vendor/AbsentBase")

        val report = LinkageAudit.run(
            listOf(LinkageAudit.JarScans("app.jar", listOf(app)), LinkageAudit.JarScans("lib.jar", listOf(child))),
            noJdk,
        )

        assertTrue(report.clean)
    }

    @Test
    fun `a member missing everywhere in a fully known hierarchy is broken`() {
        val app = scan("app/Main", refs = listOf(Triple("lib/Child", "nowhere", "()V")))
        val child = scan("lib/Child", superName = "lib/Base")
        val base = scan("lib/Base", declared = listOf("other" to "()V"))

        val report = LinkageAudit.run(
            listOf(
                LinkageAudit.JarScans("app.jar", listOf(app)),
                LinkageAudit.JarScans("lib.jar", listOf(child, base)),
            ),
            objectOnly,
        )

        assertEquals(1, report.brokenMembers.size)
    }

    @Test
    fun `groovy runtime classes are not audited as callers`() {
        val groovy = scan("org/codehaus/groovy/runtime/DefaultGroovyMethods",
            refs = listOf(Triple("lib/Util", "gone", "()V")))
        val lib = scan("lib/Util", declared = listOf("kept" to "()V"))

        val report = LinkageAudit.run(
            listOf(LinkageAudit.JarScans("groovy.jar", listOf(groovy)), LinkageAudit.JarScans("lib.jar", listOf(lib))),
            noJdk,
        )

        assertTrue(report.clean)
    }

    @Test
    fun `duplicate classes keep the first jar's copy, matching classloader order`() {
        val v1 = scan("lib/Util", declared = listOf("newApi" to "()V"))
        val v2 = scan("lib/Util", declared = listOf("oldApi" to "()V"))
        val app = scan("app/Main", refs = listOf(Triple("lib/Util", "newApi", "()V")))

        val first = LinkageAudit.run(
            listOf(
                LinkageAudit.JarScans("app.jar", listOf(app)),
                LinkageAudit.JarScans("lib-2.0.jar", listOf(v1)),
                LinkageAudit.JarScans("lib-1.0.jar", listOf(v2)),
            ),
            noJdk,
        )
        val swapped = LinkageAudit.run(
            listOf(
                LinkageAudit.JarScans("app.jar", listOf(app)),
                LinkageAudit.JarScans("lib-1.0.jar", listOf(v2)),
                LinkageAudit.JarScans("lib-2.0.jar", listOf(v1)),
            ),
            noJdk,
        )

        assertTrue("2.0 first resolves", first.clean)
        assertEquals("1.0 first breaks, exactly like the classloader would", 1, swapped.brokenMembers.size)
        assertEquals("lib-1.0.jar", swapped.brokenMembers.single().ownerJar)
    }
}
