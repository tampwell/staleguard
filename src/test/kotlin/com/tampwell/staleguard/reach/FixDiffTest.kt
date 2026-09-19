package com.tampwell.staleguard.reach

import com.tampwell.staleguard.impact.MemberKey
import com.tampwell.staleguard.impact.MemberRef
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.ConstantDynamic
import org.jetbrains.org.objectweb.asm.Handle
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.tree.ClassNode
import org.jetbrains.org.objectweb.asm.tree.FieldInsnNode
import org.jetbrains.org.objectweb.asm.tree.IincInsnNode
import org.jetbrains.org.objectweb.asm.tree.IntInsnNode
import org.jetbrains.org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.jetbrains.org.objectweb.asm.tree.JumpInsnNode
import org.jetbrains.org.objectweb.asm.tree.LabelNode
import org.jetbrains.org.objectweb.asm.tree.LdcInsnNode
import org.jetbrains.org.objectweb.asm.tree.LookupSwitchInsnNode
import org.jetbrains.org.objectweb.asm.tree.MethodInsnNode
import org.jetbrains.org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.jetbrains.org.objectweb.asm.tree.TableSwitchInsnNode
import org.jetbrains.org.objectweb.asm.tree.TypeInsnNode
import org.jetbrains.org.objectweb.asm.tree.VarInsnNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

class FixDiffTest {

    // --- the ASM oracle: instruction lists with labels as indices, constants by value ---

    private fun constant(value: Any?): String = when (value) {
        is Float -> "F:${java.lang.Float.floatToRawIntBits(value)}"
        is Double -> "D:${java.lang.Double.doubleToRawLongBits(value)}"
        is Type -> "T:${value.descriptor}"
        is Handle -> handle(value)
        is ConstantDynamic -> "CD:${value.name}${value.descriptor}@${handle(value.bootstrapMethod)}" +
            (0 until value.bootstrapMethodArgumentCount).joinToString(",", "(", ")") {
                constant(value.getBootstrapMethodArgument(it))
            }
        else -> "${value?.javaClass?.simpleName}:$value"
    }

    private fun handle(handle: Handle) = "H${handle.tag}:${handle.owner}.${handle.name}${handle.desc}:${handle.isInterface}"

    private fun asmForms(bytes: ByteArray): Map<MemberKey, List<String>> {
        val node = ClassNode()
        ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return node.methods.associate { method ->
            val index = HashMap<LabelNode, Int>()
            var count = 0
            for (insn in method.instructions) {
                if (insn is LabelNode) index[insn] = count else if (insn.opcode >= 0) count++
            }
            val tokens = ArrayList<String>()
            tokens += "A${method.access and 0xFFFF}"
            for (insn in method.instructions) {
                if (insn.opcode < 0) continue
                tokens += when (insn) {
                    is JumpInsnNode -> "${insn.opcode} ${index[insn.label]}"
                    is VarInsnNode -> "${insn.opcode} ${insn.`var`}"
                    is IntInsnNode -> "${insn.opcode} ${insn.operand}"
                    is LdcInsnNode -> "LDC ${constant(insn.cst)}"
                    is IincInsnNode -> "IINC ${insn.`var`} ${insn.incr}"
                    is TypeInsnNode -> "${insn.opcode} ${insn.desc}"
                    is FieldInsnNode -> "${insn.opcode} ${insn.owner}.${insn.name}${insn.desc}"
                    is MethodInsnNode -> "${insn.opcode} ${insn.owner}.${insn.name}${insn.desc} ${insn.itf}"
                    is InvokeDynamicInsnNode ->
                        "INDY ${insn.name}${insn.desc} ${handle(insn.bsm)} ${insn.bsmArgs.joinToString(",") { constant(it) }}"
                    is TableSwitchInsnNode -> "TS ${index[insn.dflt]} ${insn.min} ${insn.max} ${insn.labels.map { index[it] }}"
                    is LookupSwitchInsnNode -> "LS ${index[insn.dflt]} ${insn.keys.zip(insn.labels.map { index[it] })}"
                    is MultiANewArrayInsnNode -> "${insn.opcode} ${insn.desc} ${insn.dims}"
                    else -> "${insn.opcode}"
                }
            }
            for (block in method.tryCatchBlocks) {
                tokens += "X ${index[block.start]} ${index[block.end]} ${index[block.handler]} ${block.type}"
            }
            MemberKey(method.name, method.desc) to tokens
        }
    }

    private fun cachedJar(group: String, artifact: String, version: String): Path? {
        val dir = Path.of(System.getProperty("user.home"), ".gradle", "caches", "modules-2", "files-2.1", group, artifact, version)
        if (!Files.isDirectory(dir)) return null
        return Files.walk(dir).use { stream ->
            stream.filter { it.fileName.toString() == "$artifact-$version.jar" }.findFirst().orElse(null)
        }
    }

    private fun classes(jar: Path): Map<String, ByteArray> = ZipFile(jar.toFile()).use { zip ->
        zip.entries().toList()
            .filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/") && !it.name.endsWith("module-info.class") }
            .associate { it.name.removeSuffix(".class") to zip.getInputStream(it).use { input -> input.readBytes() } }
    }

    @Test
    fun `fingerprint equality agrees with ASM on every method across real releases`() {
        val pairs = listOf(
            Triple("org.junit.platform", "junit-platform-commons", "1.9.0" to "1.11.4"),
            Triple("org.junit.platform", "junit-platform-commons", "1.11.4" to "1.14.4"),
            Triple("org.junit.jupiter", "junit-jupiter-api", "5.9.0" to "5.11.4"),
            Triple("org.junit.jupiter", "junit-jupiter-api", "5.11.4" to "5.14.4"),
        ).mapNotNull { (group, artifact, versions) ->
            val before = cachedJar(group, artifact, versions.first) ?: return@mapNotNull null
            val after = cachedJar(group, artifact, versions.second) ?: return@mapNotNull null
            before to after
        }
        assumeTrue("no release pairs in the local Gradle cache", pairs.isNotEmpty())

        var compared = 0
        var changed = 0
        val disagreements = ArrayList<String>()
        for ((beforeJar, afterJar) in pairs) {
            val before = classes(beforeJar)
            val after = classes(afterJar)
            for ((name, oldBytes) in before) {
                val newBytes = after[name] ?: continue
                val oldAsm = asmForms(oldBytes)
                val newAsm = asmForms(newBytes)
                val oldMine = ClassBodyReader.fingerprints(oldBytes).methods
                val newMine = ClassBodyReader.fingerprints(newBytes).methods
                for ((key, oldTokens) in oldAsm) {
                    val newTokens = newAsm[key] ?: continue
                    compared++
                    val asmSame = oldTokens == newTokens
                    val mineSame = oldMine.getValue(key) == newMine.getValue(key)
                    if (!mineSame) changed++
                    if (asmSame != mineSame) {
                        disagreements += "${afterJar.fileName} $name.${key.name}${key.descriptor}: asm same=$asmSame, mine same=$mineSame"
                    }
                }
            }
        }
        println("FINGERPRINT ${pairs.size} release pairs, $compared methods compared, $changed changed, ${disagreements.size} disagreements")
        assertTrue(disagreements.take(20).joinToString("\n"), disagreements.isEmpty())
        assertTrue("too few methods to mean anything: $compared", compared > 1_000)
    }

    // --- constant-pool layout must not matter ---

    /**
     * Version 1: target() and changed(). Version 2 adds filler() FIRST, with
     * 300 fresh string constants, so every constant target() uses moves to a
     * new index past 255 and its ldc becomes ldc_w; changed() differs in one
     * constant.
     */
    private fun library(version: Int): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "lib/Parser", null, "java/lang/Object", null)
        if (version == 2) {
            writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "filler", "()V", null, null).apply {
                visitCode()
                repeat(300) {
                    visitLdcInsn("filler-$it")
                    visitInsn(Opcodes.POP)
                }
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "target", "(Ljava/lang/String;)Z", null, null).apply {
            visitCode()
            val done = org.jetbrains.org.objectweb.asm.Label()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitLdcInsn("\${jndi:")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "contains", "(Ljava/lang/CharSequence;)Z", false)
            visitJumpInsn(Opcodes.IFEQ, done)
            visitInsn(Opcodes.ICONST_1)
            visitInsn(Opcodes.IRETURN)
            visitLabel(done)
            visitInsn(Opcodes.ICONST_0)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "changed", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn(if (version == 1) "ldap" else "none")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun `constant pool reshuffling and ldc widening leave an unchanged method unchanged`() {
        val v1 = library(1)
        val v2 = library(2)
        // Precondition: the layout really did move under target(). ldc takes a
        // one-byte index, so a constant past 255 forces the wide ldc_w form.
        assertTrue(constantIndexOf(v1, "\${jndi:") <= 255)
        assertTrue(constantIndexOf(v2, "\${jndi:") > 255)

        val result = FixDiff.compare(InMemoryClassSource(mapOf("lib/Parser" to v1)), InMemoryClassSource(mapOf("lib/Parser" to v2)))

        assertEquals(setOf(MemberRef("lib/Parser", "changed", "()Ljava/lang/String;")), result.touched)
        assertEquals(1, result.classesChanged)
    }

    /** The constant-pool index of a string constant, read independently of the reader under test. */
    private fun constantIndexOf(bytes: ByteArray, value: String): Int {
        val reader = ClassReader(bytes)
        val buffer = CharArray(reader.maxStringLength)
        return (1 until reader.itemCount).first { index ->
            runCatching { reader.readConst(index, buffer) == value }.getOrDefault(false)
        }
    }

    // --- real releases ---

    @Test
    fun `a method the next major release removed is fix-touched`() {
        val before = cachedJar("org.junit.platform", "junit-platform-commons", "1.11.4")
        val after = cachedJar("org.junit.platform", "junit-platform-commons", "6.1.3")
        assumeTrue("junit-platform-commons releases not in the local Gradle cache", before != null && after != null)

        val result = ClasspathClassSource.open(listOf(before!!)).use { old ->
            ClasspathClassSource.open(listOf(after!!)).use { new -> FixDiff.compare(old, new) }
        }

        println("MAJOR ${result.touched.size} of ${result.methodsCompared} methods touched, ${result.classesRemoved} classes removed")
        assertTrue(
            result.touched.contains(
                MemberRef("org/junit/platform/commons/support/ReflectionSupport", "loadClass", "(Ljava/lang/String;)Ljava/util/Optional;"),
            ),
        )
    }

    @Test
    fun `a release compared with itself touches nothing`() {
        val jar = cachedJar("org.junit.platform", "junit-platform-commons", "1.11.4")
        assumeTrue("junit-platform-commons not in the local Gradle cache", jar != null)

        val result = ClasspathClassSource.open(listOf(jar!!)).use { old ->
            ClasspathClassSource.open(listOf(jar)).use { new -> FixDiff.compare(old, new) }
        }

        assertTrue(result.touched.isEmpty())
        assertTrue(result.methodsCompared > 500)
        assertTrue(result.unreadable.isEmpty())
    }
}
