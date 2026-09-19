package com.tampwell.staleguard.reach

import com.tampwell.staleguard.impact.MemberKey
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.ConstantDynamic
import org.jetbrains.org.objectweb.asm.Handle
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * The reader against the platform's ASM, method by method, over large real
 * jars: every call site, every static-initializer trigger, every
 * instantiation, including the ones hidden in invokedynamic bootstraps. A
 * hand-written bytecode walker is only trustworthy when an independent one
 * agrees with it everywhere; one mis-sized instruction desynchronizes every
 * instruction after it, which is exactly what a corpus this size catches.
 */
class ClassBodyReaderAsmOracleTest {

    private class Expected(val access: Int) {
        val calls = LinkedHashSet<CallSite>()
        val initializes = LinkedHashSet<String>()
        val instantiates = LinkedHashSet<String>()
    }

    /** The platform's test classloader leaves codeSource empty; the resource URL still names the jar. */
    private fun jarOf(className: String): Path? = runCatching {
        val url = javaClass.classLoader.getResource(className.replace('.', '/') + ".class") ?: return null
        if (url.protocol != "jar") return null
        val file = url.path.substringBefore("!/")
        Path.of(java.net.URI(file))
    }.getOrNull()?.takeIf { it.fileName.toString().endsWith(".jar") }

    private fun asmBodies(bytes: ByteArray): Map<MemberKey, Expected> {
        val result = LinkedHashMap<MemberKey, Expected>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val expected = Expected(access)
                    result[MemberKey(name, descriptor)] = expected
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean,
                        ) {
                            val dispatch = when (opcode) {
                                Opcodes.INVOKESTATIC -> Dispatch.STATIC
                                Opcodes.INVOKESPECIAL -> Dispatch.SPECIAL
                                else -> Dispatch.VIRTUAL
                            }
                            expected.calls += CallSite(owner, MemberKey(name, descriptor), dispatch)
                            if (opcode == Opcodes.INVOKESTATIC) expected.initializes += owner
                        }

                        override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                            if (opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC) expected.initializes += owner
                        }

                        override fun visitTypeInsn(opcode: Int, type: String) {
                            if (opcode == Opcodes.NEW) {
                                expected.instantiates += type
                                expected.initializes += type
                            }
                        }

                        override fun visitInvokeDynamicInsn(
                            name: String,
                            descriptor: String,
                            bootstrapMethodHandle: Handle,
                            vararg bootstrapMethodArguments: Any,
                        ) {
                            handle(bootstrapMethodHandle)
                            bootstrapMethodArguments.forEach(::constant)
                        }

                        override fun visitLdcInsn(value: Any) = constant(value)

                        private fun constant(value: Any) {
                            when (value) {
                                is Handle -> handle(value)
                                is ConstantDynamic -> {
                                    handle(value.bootstrapMethod)
                                    for (i in 0 until value.bootstrapMethodArgumentCount) {
                                        constant(value.getBootstrapMethodArgument(i))
                                    }
                                }
                            }
                        }

                        private fun handle(handle: Handle) {
                            val key = MemberKey(handle.name, handle.desc)
                            when (handle.tag) {
                                Opcodes.H_GETSTATIC, Opcodes.H_PUTSTATIC -> expected.initializes += handle.owner
                                Opcodes.H_GETFIELD, Opcodes.H_PUTFIELD -> Unit
                                Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKEINTERFACE ->
                                    expected.calls += CallSite(handle.owner, key, Dispatch.VIRTUAL)
                                Opcodes.H_INVOKESTATIC -> {
                                    expected.calls += CallSite(handle.owner, key, Dispatch.STATIC)
                                    expected.initializes += handle.owner
                                }
                                Opcodes.H_INVOKESPECIAL -> expected.calls += CallSite(handle.owner, key, Dispatch.SPECIAL)
                                Opcodes.H_NEWINVOKESPECIAL -> {
                                    expected.calls += CallSite(handle.owner, key, Dispatch.SPECIAL)
                                    expected.instantiates += handle.owner
                                    expected.initializes += handle.owner
                                }
                            }
                        }
                    }
                }
            },
            0,
        )
        return result
    }

    @Test
    fun `agrees with ASM on every method of large real jars`() {
        // Deliberately varied producers: javac across Java versions, kotlinc,
        // and libraries heavy on lambdas, switches, and string concatenation.
        val jars = listOf(
            "com.intellij.openapi.util.text.StringUtil",
            "com.intellij.psi.PsiElement",
            "com.intellij.openapi.editor.Editor",
            "kotlin.Unit",
            "kotlinx.coroutines.CoroutineScope",
            "com.google.common.collect.ImmutableList",
            "com.fasterxml.jackson.databind.ObjectMapper",
            "com.fasterxml.jackson.core.JsonParser",
            "com.google.gson.Gson",
            "org.apache.commons.lang3.StringUtils",
            "it.unimi.dsi.fastutil.ints.IntArrayList",
            "org.junit.Test",
        ).mapNotNull(::jarOf).distinct()
        assertTrue("need several real jars on the test classpath, found $jars", jars.size >= 3)

        var classes = 0
        var methods = 0
        var callSites = 0
        var oracleSkipped = 0
        val mismatches = mutableListOf<String>()
        for (jar in jars) {
            ZipFile(jar.toFile()).use { zip ->
                for (entry in zip.entries()) {
                    if (!entry.name.endsWith(".class")) continue
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    // A class newer than the platform's ASM cannot be judged by it.
                    val expected = runCatching { asmBodies(bytes) }.getOrNull()
                    if (expected == null) {
                        oracleSkipped++
                        continue
                    }
                    val attempt = runCatching { ClassBodyReader.read(bytes) }
                    val actual = attempt.getOrNull()
                    if (actual == null) {
                        mismatches += "${jar.fileName}!${entry.name}: reader threw ${attempt.exceptionOrNull()}"
                        continue
                    }
                    classes++
                    if (actual.methods.keys != expected.keys) {
                        mismatches += "${entry.name}: method sets differ"
                        continue
                    }
                    for ((key, want) in expected) {
                        methods++
                        val got = actual.methods.getValue(key)
                        callSites += want.calls.size
                        // ASM folds pseudo-flags such as ACC_DEPRECATED (0x20000) into the
                        // access word; the JVM's own flags are the low 16 bits.
                        val wantAccess = want.access and 0xFFFF
                        if (got.access != wantAccess || got.calls.toSet() != want.calls ||
                            got.initializes.toSet() != want.initializes || got.instantiates.toSet() != want.instantiates
                        ) {
                            mismatches += "${entry.name} ${key.name}${key.descriptor}: " +
                                "access ${got.access} vs $wantAccess, " +
                                "calls ${got.calls.toSet() - want.calls} vs ${want.calls - got.calls.toSet()}, " +
                                "init ${got.initializes.toSet() - want.initializes} vs ${want.initializes - got.initializes.toSet()}, " +
                                "new ${got.instantiates.toSet() - want.instantiates} vs ${want.instantiates - got.instantiates.toSet()}"
                        }
                    }
                }
            }
        }
        println(
            "ORACLE ${jars.size} jars, $classes classes, $methods methods, $callSites call sites, " +
                "$oracleSkipped skipped by ASM, ${mismatches.size} mismatches",
        )
        assertTrue(mismatches.take(20).joinToString("\n"), mismatches.isEmpty())
        assertTrue("corpus too small to mean anything: $methods methods", methods > 100_000)
    }
}
