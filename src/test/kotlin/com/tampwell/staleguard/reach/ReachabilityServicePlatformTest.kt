package com.tampwell.staleguard.reach

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.tampwell.staleguard.impact.MemberRef
import com.tampwell.staleguard.inspection.VulnerabilityProblems
import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.security.AdvisoryPeek
import com.tampwell.staleguard.security.OsvAdvisory
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path

/**
 * The whole feature through the platform: a module whose compiled output is
 * a tiny app and whose library is the real log4j-core 2.14.1, one seeded
 * Log4Shell advisory, one seeded fix diff (so nothing touches the network),
 * then the service run exactly as the action runs it. Proves the wiring:
 * scopes from module roots, coordinates from the jar's cache path, the diff
 * from cache, the walk, the recorded verdict, and the editor sentence.
 */
class ReachabilityServicePlatformTest : BasePlatformTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = DESCRIPTOR

    private val core = Coordinates("org.apache.logging.log4j", "log4j-core")
    private val log4shell = OsvAdvisory(
        id = "GHSA-jfh8-c2jp-5v3q",
        cveId = "CVE-2021-44228",
        severity = "CRITICAL",
        summary = "Remote code execution in Log4j",
        fixedVersion = "2.15.0",
    )

    override fun setUp() {
        super.setUp()
        val service = ReachabilityService.getInstance(project)
        service.advisories = { coordinates, version ->
            val found = if (coordinates == core && version == "2.14.1") listOf(log4shell) else emptyList()
            AdvisoryPeek(found, fetchedAtMillis = System.currentTimeMillis())
        }
        FixDiffCache(Path.of(PathManager.getSystemPath(), "staleguard", "fix-diffs")).write(
            core, "2.14.1", "2.15.0",
            FixDiff.Result(
                touched = setOf(
                    MemberRef(
                        "org/apache/logging/log4j/core/lookup/JndiLookup", "lookup",
                        "(Lorg/apache/logging/log4j/core/LogEvent;Ljava/lang/String;)Ljava/lang/String;",
                    ),
                    MemberRef("org/apache/logging/log4j/core/net/JndiManager", "lookup", "(Ljava/lang/String;)Ljava/lang/Object;"),
                ),
                methodsCompared = 2,
                classesRemoved = 0,
                classesChanged = 2,
                unreadable = emptyList(),
            ),
        )
    }

    private fun jarsPresent(): Boolean = Files.isRegularFile(LOG4J_API) && Files.isRegularFile(LOG4J_CORE)

    private fun compileApp(body: (MethodVisitor) -> Unit) {
        Files.createDirectories(OUTPUT.resolve("demo"))
        Files.write(OUTPUT.resolve("demo/App.class"), app(body))
    }

    fun `test logging a string is reported reached, with the path in the editor sentence`() {
        if (!jarsPresent()) return
        compileApp { method ->
            method.visitLdcInsn("demo")
            method.visitMethodInsn(
                Opcodes.INVOKESTATIC, "org/apache/logging/log4j/LogManager", "getLogger",
                "(Ljava/lang/String;)Lorg/apache/logging/log4j/Logger;", false,
            )
            method.visitVarInsn(Opcodes.ALOAD, 1)
            method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/apache/logging/log4j/Logger", "error", "(Ljava/lang/String;)V", true)
        }

        val summary = ReachabilityService.getInstance(project).check(EmptyProgressIndicator())

        assertEquals(1, summary.vulnerable)
        assertEquals(1, summary.reached)
        val verdict = ReachabilityState.getInstance(project).verdictFor(core, "2.14.1", log4shell.id)
        verdict as ReachVerdict.Reached
        assertTrue(verdict.inProduction)
        assertEquals("App.handle", verdict.path.first())
        val note = VulnerabilityProblems.reachabilityNote(project, core, "2.14.1", log4shell)
        assertTrue(note, note.contains("reaches what the fix changed") && note.contains("App.handle"))
        // Version-exact: a bumped declaration no longer carries the verdict.
        assertEquals("", VulnerabilityProblems.reachabilityNote(project, core, "2.17.1", log4shell))
    }

    fun `test using only a string utility is reported not reached`() {
        if (!jarsPresent()) return
        compileApp { method ->
            method.visitVarInsn(Opcodes.ALOAD, 1)
            method.visitMethodInsn(
                Opcodes.INVOKESTATIC, "org/apache/logging/log4j/util/Strings", "isEmpty", "(Ljava/lang/CharSequence;)Z", false,
            )
            method.visitInsn(Opcodes.POP)
        }

        val summary = ReachabilityService.getInstance(project).check(EmptyProgressIndicator())

        assertEquals(1, summary.notReached)
        assertEquals(
            ReachVerdict.NotReached(2),
            ReachabilityState.getInstance(project).verdictFor(core, "2.14.1", log4shell.id),
        )
    }

    fun `test an unbuilt module says so instead of claiming anything`() {
        if (!jarsPresent()) return
        Files.walk(OUTPUT).use { stream ->
            stream.sorted(Comparator.reverseOrder()).filter { it != OUTPUT }.forEach(Files::deleteIfExists)
        }

        val summary = ReachabilityService.getInstance(project).check(EmptyProgressIndicator())

        assertTrue(summary.notBuilt)
        assertEquals(
            ReachVerdict.Unknown(ReachVerdict.Reason.NOT_BUILT),
            ReachabilityState.getInstance(project).verdictFor(core, "2.14.1", log4shell.id),
        )
    }

    private companion object {
        val M2: Path = Path.of(System.getProperty("user.home"), ".m2", "repository")
        val LOG4J_API: Path = M2.resolve("org/apache/logging/log4j/log4j-api/2.17.2/log4j-api-2.17.2.jar")
        val LOG4J_CORE: Path = M2.resolve("org/apache/logging/log4j/log4j-core/2.14.1/log4j-core-2.14.1.jar")
        val OUTPUT: Path = Files.createTempDirectory("reach-module-output")

        val DESCRIPTOR = object : LightProjectDescriptor() {
            override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
                model.getModuleExtension(CompilerModuleExtension::class.java).apply {
                    inheritCompilerOutputPath(false)
                    setCompilerOutputPath(VfsUtilCore.pathToUrl(OUTPUT.toString()))
                }
                val library = model.moduleLibraryTable.createLibrary("log4j")
                library.modifiableModel.apply {
                    for (jar in listOf(LOG4J_API, LOG4J_CORE)) {
                        addRoot(VfsUtil.getUrlForLibraryRoot(jar.toFile()), OrderRootType.CLASSES)
                    }
                    commit()
                }
            }
        }

        fun app(body: (MethodVisitor) -> Unit): ByteArray {
            val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "demo/App", null, "java/lang/Object", null)
            writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
            writer.visitMethod(Opcodes.ACC_PUBLIC, "handle", "(Ljava/lang/String;)V", null, null).apply {
                visitCode()
                body(this)
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
            writer.visitEnd()
            return writer.toByteArray()
        }
    }
}
