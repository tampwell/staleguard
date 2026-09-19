package com.tampwell.staleguard.reach

import com.tampwell.staleguard.impact.MemberRef
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The model against the real thing: log4j-core 2.14.1, the Log4Shell
 * release, where the vulnerable lookup is created reflectively by name and
 * reached through a chain of plugin-built appenders, layouts and converters.
 * A reachability engine that misses this path is not fit to say "not
 * reached" about anything.
 *
 * Uses the developer's local Maven repository and skips where the jars are
 * absent, rather than vendoring a known-vulnerable artifact into the repo.
 */
class ReachabilityRealWorldTest {

    private val m2 = Path.of(System.getProperty("user.home"), ".m2", "repository")
    private val log4jApi = m2.resolve("org/apache/logging/log4j/log4j-api/2.17.2/log4j-api-2.17.2.jar")
    private val log4jCore = m2.resolve("org/apache/logging/log4j/log4j-core/2.14.1/log4j-core-2.14.1.jar")

    private val jndiLookup = MemberRef(
        "org/apache/logging/log4j/core/lookup/JndiLookup",
        "lookup",
        "(Lorg/apache/logging/log4j/core/LogEvent;Ljava/lang/String;)Ljava/lang/String;",
    )
    private val jndiManager = MemberRef(
        "org/apache/logging/log4j/core/net/JndiManager",
        "lookup",
        "(Ljava/lang/String;)Ljava/lang/Object;",
    )

    /** demo/App with one method: invoke [owner].[name][descriptor] on its String argument. */
    private fun app(body: (org.jetbrains.org.objectweb.asm.MethodVisitor) -> Unit): ByteArray {
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

    private fun analyze(appBytes: ByteArray): Reachability.Result {
        val root = Files.createTempDirectory("reach-app")
        Files.createDirectories(root.resolve("demo"))
        Files.write(root.resolve("demo/App.class"), appBytes)
        return ClasspathClassSource.open(listOf(root, log4jApi, log4jCore)).use { source ->
            Reachability.analyze(source, listOf("demo/App"))
        }
    }

    private fun Reachability.Result.describe(method: MemberRef): String =
        path(method)?.joinToString("\n  -> ") { "${it.method.owner.substringAfterLast('/')}.${it.method.name} [${it.via}]" }
            ?: "not reached"

    @Test
    fun `logging a string reaches the Log4Shell lookup`() {
        assumeTrue("log4j jars not in the local Maven repository", Files.isRegularFile(log4jApi) && Files.isRegularFile(log4jCore))

        val result = analyze(
            app { method ->
                method.visitLdcInsn("demo")
                method.visitMethodInsn(
                    Opcodes.INVOKESTATIC, "org/apache/logging/log4j/LogManager", "getLogger",
                    "(Ljava/lang/String;)Lorg/apache/logging/log4j/Logger;", false,
                )
                method.visitVarInsn(Opcodes.ALOAD, 1)
                method.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE, "org/apache/logging/log4j/Logger", "error", "(Ljava/lang/String;)V", true,
                )
            },
        )

        println("LOG4SHELL reached ${result.reachedMethods} methods in ${result.millis}ms, complete=${result.complete} ${result.gaps}")
        println("LOG4SHELL JndiLookup.lookup:\n  ${result.describe(jndiLookup)}")
        println("LOG4SHELL JndiManager.lookup:\n  ${result.describe(jndiManager)}")
        assertTrue("Log4Shell's JNDI lookup must be reached", result.reaches(jndiLookup))
        assertTrue("the JNDI manager behind it must be reached", result.reaches(jndiManager))
    }

    @Test
    fun `using only a string utility from the same library does not reach it`() {
        assumeTrue("log4j jars not in the local Maven repository", Files.isRegularFile(log4jApi) && Files.isRegularFile(log4jCore))

        val result = analyze(
            app { method ->
                method.visitVarInsn(Opcodes.ALOAD, 1)
                method.visitMethodInsn(
                    Opcodes.INVOKESTATIC, "org/apache/logging/log4j/util/Strings", "isEmpty",
                    "(Ljava/lang/CharSequence;)Z", false,
                )
                method.visitInsn(Opcodes.POP)
            },
        )

        println("CONTROL reached ${result.reachedMethods} methods, complete=${result.complete}")
        assertTrue(result.complete)
        assertFalse(result.describe(jndiLookup), result.reaches(jndiLookup))
        assertFalse(result.reaches(jndiManager))
    }
}
