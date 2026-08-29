package com.tampwell.staleguard.impact

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The platform half of the classpath doctor: SDK-backed JDK member resolution
 * and the service run end to end. The light fixture ships a mock JDK with the
 * java.lang core, which is exactly enough to prove the PSI path works and
 * that its answers reach the audit.
 */
class ClasspathLinkagePlatformTest : BasePlatformTestCase() {

    fun `test an empty classpath audits clean and fast`() {
        val result = ClasspathLinkageService.getInstance(project).audit(EmptyProgressIndicator())

        assertTrue(result.report.clean)
        assertEquals(0, result.report.jarCount)
    }

    override fun setUp() {
        super.setUp()
        // The light fixture ships no JDK, so give the index one platform
        // class to answer for; javax/ is in the platform prefix list.
        myFixture.addFileToProject(
            "javax/swing/JPanel.java",
            """
            package javax.swing;

            public class JPanel {
                public void repaint() {}
            }
            """.trimIndent(),
        )
    }

    fun `test a member inherited from an indexed platform class resolves`() {
        val child = ClassScan(
            api = ClassApi("lib/Child", "javax/swing/JPanel", emptyList(), emptySet()),
            declaredAll = emptySet(),
            refs = emptySet(),
        )
        val caller = ClassScan(
            api = ClassApi("app/Main", "java/lang/Object", emptyList(), emptySet()),
            declaredAll = emptySet(),
            refs = setOf(MemberRef("lib/Child", "repaint", "()V")),
        )

        assertTrue(auditOf(caller, child).clean)
    }

    fun `test a member the indexed platform class lacks is broken`() {
        val child = ClassScan(
            api = ClassApi("lib/Child", "javax/swing/JPanel", emptyList(), emptySet()),
            declaredAll = emptySet(),
            refs = emptySet(),
        )
        val caller = ClassScan(
            api = ClassApi("app/Main", "java/lang/Object", emptyList(), emptySet()),
            declaredAll = emptySet(),
            refs = setOf(MemberRef("lib/Child", "definitelyNotAMethod", "()V")),
        )

        val report = auditOf(caller, child)

        assertEquals(1, report.brokenMembers.size)
        assertEquals("definitelyNotAMethod", report.brokenMembers.single().ref.name)
    }

    fun `test an unindexed platform class cannot prove absence and stays quiet`() {
        // No JDK in this fixture, so java/lang/Object is unindexed: the walk
        // must treat it as resolved rather than accuse.
        val child = ClassScan(
            api = ClassApi("lib/Child", "java/lang/Object", emptyList(), emptySet()),
            declaredAll = emptySet(),
            refs = emptySet(),
        )
        val caller = ClassScan(
            api = ClassApi("app/Main", "java/lang/Object", emptyList(), emptySet()),
            declaredAll = emptySet(),
            refs = setOf(MemberRef("lib/Child", "whatever", "()V")),
        )

        assertTrue(auditOf(caller, child).clean)
    }

    fun `test a direct ref to a jdk-owned class is out of scope by design`() {
        // Jars in the wild are compiled against many JDKs; auditing their
        // direct JDK calls against this project's SDK is a different check
        // with a different noise profile, measured and deliberately excluded.
        val caller = ClassScan(
            api = ClassApi("app/Main", "java/lang/Object", emptyList(), emptySet()),
            declaredAll = emptySet(),
            refs = setOf(MemberRef("java/lang/String", "definitelyNotAMethod", "()V")),
        )

        assertTrue(auditOf(caller).clean)
    }

    private fun auditOf(vararg scans: ClassScan): LinkageAudit.Report {
        // The REAL production lookup, backed by the fixture's mock JDK.
        val lookup = PsiPlatformMembers(project)
        return LinkageAudit.run(
            listOf(LinkageAudit.JarScans("test.jar", scans.toList())),
            lookup::has,
        )
    }
}
