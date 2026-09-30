package com.tampwell.staleguard.checkup

import com.intellij.openapi.application.ApplicationManager
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
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.tampwell.staleguard.impact.MemberRef
import com.tampwell.staleguard.reach.FixDiff
import com.tampwell.staleguard.reach.FixDiffCache
import com.tampwell.staleguard.reach.ReachabilityService
import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.security.AdvisoryPeek
import com.tampwell.staleguard.security.OsvAdvisory
import com.tampwell.staleguard.settings.StaleguardSettings
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path

/**
 * The checkup on a project whose only vulnerable artifact is a classpath jar
 * no build file declares (the real log4j-core 2.14.1): it must still lead
 * the report, marked reached, with the path. Offline, with the advisory and
 * the fix diff seeded, so nothing touches the network.
 */
class CheckupLog4ShellPlatformTest : BasePlatformTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = DESCRIPTOR

    private val core = Coordinates("org.apache.logging.log4j", "log4j-core")
    private val log4shell = OsvAdvisory("GHSA-jfh8-c2jp-5v3q", "CVE-2021-44228", "CRITICAL", "Remote code execution in Log4j", "2.15.0")
    private var wasOffline = false

    override fun setUp() {
        super.setUp()
        val state = StaleguardSettings.getInstance().state
        wasOffline = state.offlineMode
        state.offlineMode = true
        val seeded: (Coordinates, String) -> List<OsvAdvisory> = { coordinates, version ->
            if (coordinates == core && version == "2.14.1") listOf(log4shell) else emptyList()
        }
        ReachabilityService.getInstance(project).advisories = { c, v -> AdvisoryPeek(seeded(c, v), System.currentTimeMillis()) }
        CheckupService.getInstance(project).advisories = seeded
        FixDiffCache(Path.of(PathManager.getSystemPath(), "staleguard", "fix-diffs")).write(
            core, "2.14.1", "2.15.0",
            FixDiff.Result(
                setOf(
                    MemberRef(
                        "org/apache/logging/log4j/core/lookup/JndiLookup", "lookup",
                        "(Lorg/apache/logging/log4j/core/LogEvent;Ljava/lang/String;)Ljava/lang/String;",
                    ),
                ),
                methodsCompared = 1, classesRemoved = 0, classesChanged = 1, unreadable = emptyList(),
            ),
        )
    }

    override fun tearDown() {
        try {
            StaleguardSettings.getInstance().state.offlineMode = wasOffline
        } finally {
            super.tearDown()
        }
    }

    fun `test a reached classpath-only vulnerability leads the report with its path`() {
        if (!Files.isRegularFile(LOG4J_API) || !Files.isRegularFile(LOG4J_CORE)) return
        Files.createDirectories(OUTPUT.resolve("demo"))
        Files.write(OUTPUT.resolve("demo/App.class"), loggingApp())

        val future = ApplicationManager.getApplication().executeOnPooledThread<CheckupService.Outcome> {
            CheckupService.getInstance(project).run(EmptyProgressIndicator())
        }
        val report = PlatformTestUtil.waitForFuture(future, 120_000).report

        val top = report.findings.first()
        assertEquals(CheckupReport.Tier.FIX_NOW, top.tier)
        assertTrue(top.title, top.title.startsWith("log4j-core 2.14.1 (transitive): CVE-2021-44228"))
        assertTrue(top.title, top.title.contains("your code reaches what the fix changed"))
        assertTrue(top.detail, top.detail!!.startsWith("App.handle") && top.detail!!.endsWith("JndiLookup.lookup"))
        assertEquals(75, report.score)
        assertTrue(report.inputs.ownCodeChecked)
    }

    private companion object {
        val M2: Path = Path.of(System.getProperty("user.home"), ".m2", "repository")
        val LOG4J_API: Path = M2.resolve("org/apache/logging/log4j/log4j-api/2.17.2/log4j-api-2.17.2.jar")
        val LOG4J_CORE: Path = M2.resolve("org/apache/logging/log4j/log4j-core/2.14.1/log4j-core-2.14.1.jar")
        val OUTPUT: Path = Files.createTempDirectory("checkup-module-output")

        val DESCRIPTOR = object : LightProjectDescriptor() {
            override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
                model.getModuleExtension(CompilerModuleExtension::class.java).apply {
                    inheritCompilerOutputPath(false)
                    setCompilerOutputPath(VfsUtilCore.pathToUrl(OUTPUT.toString()))
                }
                model.moduleLibraryTable.createLibrary("log4j").modifiableModel.apply {
                    for (jar in listOf(LOG4J_API, LOG4J_CORE)) addRoot(VfsUtil.getUrlForLibraryRoot(jar.toFile()), OrderRootType.CLASSES)
                    commit()
                }
            }
        }

        fun loggingApp(): ByteArray {
            val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "demo/App", null, "java/lang/Object", null)
            writer.visitMethod(Opcodes.ACC_PUBLIC, "handle", "(Ljava/lang/String;)V", null, null).apply {
                visitCode()
                visitLdcInsn("demo")
                visitMethodInsn(
                    Opcodes.INVOKESTATIC, "org/apache/logging/log4j/LogManager", "getLogger",
                    "(Ljava/lang/String;)Lorg/apache/logging/log4j/Logger;", false,
                )
                visitVarInsn(Opcodes.ALOAD, 1)
                visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/apache/logging/log4j/Logger", "error", "(Ljava/lang/String;)V", true)
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
            writer.visitEnd()
            return writer.toByteArray()
        }
    }
}
