package com.tampwell.staleguard.impact

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OwnCodeAuditTest {

    private fun scans(vararg names: String) = LinkageAudit.JarScans(
        "module",
        names.map { ClassScan(ClassApi(it, "java/lang/Object", emptyList(), emptySet()), emptySet(), emptySet()) },
    )

    private fun output(name: String, scans: LinkageAudit.JarScans?, at: Long? = 1000L) =
        OwnCodeAudit.ModuleOutput(name, scans, at)

    @Test
    fun `nothing built claims nothing`() {
        val standing = OwnCodeAudit.standing(
            listOf(output("app", null, at = null), output("lib", scans(), at = null)),
        )

        assertEquals(OwnCodeAudit.Standing.NothingBuilt, standing)
        assertFalse(OwnCodeAudit.mayClaimClean(standing))
    }

    @Test
    fun `a fully built project may claim clean, dated by the newest class`() {
        val standing = OwnCodeAudit.standing(
            listOf(
                output("app", scans("app/Main"), at = 5_000L),
                output("lib", scans("lib/Util"), at = 9_000L),
            ),
        )

        assertTrue(OwnCodeAudit.mayClaimClean(standing))
        assertEquals(9_000L, (standing as OwnCodeAudit.Standing.Built).asOfMillis)
    }

    @Test
    fun `a partial build forbids clean and names the unchecked modules`() {
        val standing = OwnCodeAudit.standing(
            listOf(
                output("app", scans("app/Main"), at = 5_000L),
                output("never-built", null, at = null),
            ),
        )

        assertFalse("clean must not be claimable when a module was never checked", OwnCodeAudit.mayClaimClean(standing))
        assertEquals(listOf("never-built"), (standing as OwnCodeAudit.Standing.PartiallyBuilt).missingModules)
    }

    @Test
    fun `an empty output directory counts as unbuilt, not as a clean module`() {
        val standing = OwnCodeAudit.standing(
            listOf(output("app", scans("app/Main")), output("empty", scans())),
        )

        assertTrue(standing is OwnCodeAudit.Standing.PartiallyBuilt)
    }

    @Test
    fun `only built modules join the audit`() {
        val outputs = listOf(
            output("app", scans("app/Main")),
            output("empty", scans()),
            output("missing", null),
        )

        assertEquals(1, OwnCodeAudit.auditableScans(outputs).size)
    }
}

class ScanDirectoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun ownClassBytes(internalName: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/$internalName.class")).use { it.readBytes() }

    @Test
    fun `a class tree scans with nested packages`() {
        val root = temp.newFolder("out").toPath()
        val target = root.resolve("com/tampwell/staleguard/impact")
        Files.createDirectories(target)
        Files.write(target.resolve("MemberKey.class"), ownClassBytes("com/tampwell/staleguard/impact/MemberKey"))
        Files.write(target.resolve("JvmDescriptors.class"), ownClassBytes("com/tampwell/staleguard/impact/JvmDescriptors"))

        val scans = JarScanner.scanDirectory(root, "module 'app'")

        assertNotNull(scans)
        assertEquals("module 'app'", scans!!.jarName)
        assertEquals(2, scans.classes.size)
        assertNotNull(JarScanner.newestClassMillis(root))
    }

    @Test
    fun `a missing directory is null, never an empty clean-looking result`() {
        assertNull(JarScanner.scanDirectory(temp.root.toPath().resolve("never-built"), "module 'x'"))
        assertNull(JarScanner.newestClassMillis(temp.root.toPath().resolve("never-built")))
    }

    @Test
    fun `a directory scan feeds the audit exactly like a jar would`() {
        val root = temp.newFolder("out2").toPath()
        val target = root.resolve("com/tampwell/staleguard/impact")
        Files.createDirectories(target)
        Files.write(
            target.resolve("ClasspathClassLookup.class"),
            ownClassBytes("com/tampwell/staleguard/impact/ClasspathClassLookup"),
        )

        val own = JarScanner.scanDirectory(root, "module 'app'")!!
        // Its refs point at classes absent here whose packages are also absent
        // (the rest of the plugin), so the audit must stay conservative-quiet.
        val report = LinkageAudit.run(listOf(own)) { _, _ -> true }

        assertEquals(1, report.jarCount)
        assertTrue(report.refCount > 0)
        assertTrue(report.brokenMembers.isEmpty())
    }
}
