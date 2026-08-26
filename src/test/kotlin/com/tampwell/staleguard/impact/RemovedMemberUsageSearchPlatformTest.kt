package com.tampwell.staleguard.impact

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The one part of the analysis that pure unit tests cannot reach: turning a JVM
 * descriptor back into the right PSI member and finding its call sites.
 *
 * Everything here is project source rather than a real library, which changes
 * nothing that matters — the search resolves the owner class through
 * JavaPsiFacade and looks for references in the project scope either way — and
 * it means erasure, varargs, overloads and constructors get exercised against
 * real Java PSI instead of a mock.
 *
 * Parameter types are all declared in the fixture rather than taken from
 * java.lang or java.util. The light fixture has no JDK, so a JDK type would not
 * resolve and its canonical text would degrade to a short name, making the test
 * fail for a reason that cannot happen in a real project.
 */
class RemovedMemberUsageSearchPlatformTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("lib/Thing.java", "package lib;\n\npublic class Thing {}\n")
        myFixture.addFileToProject("lib/Box.java", "package lib;\n\npublic class Box<T> {}\n")
        myFixture.addFileToProject(
            "lib/Lib.java",
            """
            package lib;

            public class Lib {
                public static Thing FIELD;

                public Lib(int i) {}

                public void gone(Thing t) {}

                public void gone(int i) {}

                public void kept() {}

                public void generic(Box<Thing> box) {}

                public void variadic(Thing... parts) {}

                public <T extends Thing> void bounded(T value) {}
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "app/Caller.java",
            """
            package app;

            import lib.Box;
            import lib.Lib;
            import lib.Thing;

            public class Caller {
                void run() {
                    Lib lib = new Lib(1);
                    lib.gone(new Thing());
                    lib.gone(2);
                    lib.kept();
                    Thing f = Lib.FIELD;
                    lib.generic(new Box<Thing>());
                    lib.variadic(new Thing(), new Thing());
                    lib.bounded(new Thing());
                }
            }
            """.trimIndent(),
        )
    }

    private fun find(vararg members: MemberRef) =
        RemovedMemberUsageSearch.find(project, members.toList(), EmptyProgressIndicator())

    private fun linesFor(member: MemberRef): List<Int> =
        find(member).usages.singleOrNull()?.locations?.map { it.line } ?: emptyList()

    fun `test a call to a removed method is found at its call site`() {
        val result = find(MemberRef("lib/Lib", "kept", "()V"))

        assertEquals(1, result.usages.size)
        assertEquals(1, result.usages.single().locations.size)
        assertTrue(result.searchedAll)
    }

    fun `test overloads are told apart by descriptor, not by name`() {
        // gone(Thing) and gone(int) are called on different lines; each
        // descriptor must find only its own call. Matching on name and arity
        // alone would report both for either.
        val objectOverload = linesFor(MemberRef("lib/Lib", "gone", "(Llib/Thing;)V"))
        val intOverload = linesFor(MemberRef("lib/Lib", "gone", "(I)V"))

        assertEquals(1, objectOverload.size)
        assertEquals(1, intOverload.size)
        assertTrue(
            "the two overloads must resolve to different call sites",
            objectOverload.single() != intOverload.single(),
        )
    }

    fun `test a generic parameter matches its erasure`() {
        // The descriptor says Box; the PSI says Box<Thing>.
        assertEquals(1, linesFor(MemberRef("lib/Lib", "generic", "(Llib/Box;)V")).size)
    }

    fun `test a varargs parameter matches the array descriptor the compiler writes`() {
        assertEquals(1, linesFor(MemberRef("lib/Lib", "variadic", "([Llib/Thing;)V")).size)
    }

    fun `test a type variable matches the erasure of its bound`() {
        assertEquals(1, linesFor(MemberRef("lib/Lib", "bounded", "(Llib/Thing;)V")).size)
    }

    fun `test a constructor is found through its class name`() {
        assertEquals(1, linesFor(MemberRef("lib/Lib", "<init>", "(I)V")).size)
    }

    fun `test a field read is found`() {
        assertEquals(1, linesFor(MemberRef("lib/Lib", "FIELD", "Llib/Thing;")).size)
    }

    fun `test a removed member nobody calls produces no usage`() {
        val result = find(MemberRef("lib/Lib", "neverCalled", "()V"))

        assertTrue(result.usages.isEmpty())
        assertTrue(result.searchedAll)
    }

    fun `test a member whose owner is not on the classpath is silently skipped`() {
        val result = find(MemberRef("nowhere/Absent", "kept", "()V"))

        assertTrue(result.usages.isEmpty())
    }

    fun `test a descriptor matching no overload reports nothing rather than guessing`() {
        // Same name, a signature that does not exist. Falling back to any
        // overload here would invent a breakage the user does not have.
        val result = find(MemberRef("lib/Lib", "gone", "(JJ)V"))

        assertTrue(result.usages.isEmpty())
    }

    fun `test locations carry a project-relative path and a one-based line`() {
        val location = find(MemberRef("lib/Lib", "kept", "()V")).usages.single().locations.single()

        assertTrue(
            "expected a path ending in Caller.java, got ${location.presentablePath}",
            location.presentablePath.endsWith("Caller.java"),
        )
        assertTrue("expected a one-based line, got ${location.line}", location.line > 0)
        assertTrue(location.fileUrl.endsWith("Caller.java"))
    }

    fun `test several removed members are reported together`() {
        val result = find(
            MemberRef("lib/Lib", "kept", "()V"),
            MemberRef("lib/Lib", "gone", "(Llib/Thing;)V"),
            MemberRef("lib/Lib", "neverCalled", "()V"),
        )

        assertEquals(2, result.usages.size)
        assertEquals(2, result.usages.sumOf { it.locations.size })
    }
}
