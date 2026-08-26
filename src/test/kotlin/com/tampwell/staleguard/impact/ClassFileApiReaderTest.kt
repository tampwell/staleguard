package com.tampwell.staleguard.impact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reads this module's own compiled classes, so the fixtures are real class
 * files produced by the same toolchain that builds the plugin, and hand-built
 * bytes for the format corners that no modern compiler emits any more.
 */
class ClassFileApiReaderTest {

    private fun readOwn(internalName: String): ClassApi? {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/$internalName.class")) {
            "compiled class not on the test classpath: $internalName"
        }.use { it.readBytes() }
        return ClassFileApiReader.read(bytes)
    }

    @Test
    fun `reads a public class and its members`() {
        val api = checkNotNull(readOwn("com/tampwell/staleguard/impact/MemberKey"))
        assertEquals("com/tampwell/staleguard/impact/MemberKey", api.internalName)
        assertEquals("java/lang/Object", api.superName)
        assertTrue(api.members.any { it.name == "getName" && it.descriptor == "()Ljava/lang/String;" })
        assertTrue(api.members.any { it.name == "<init>" && it.descriptor.startsWith("(") })
    }

    @Test
    fun `records interfaces so inherited members can be resolved`() {
        val api = checkNotNull(readOwn("com/tampwell/staleguard/impact/ClasspathClassLookup"))
        assertTrue(
            "expected the lookup interfaces to be recorded, got ${api.interfaces}",
            api.interfaces.contains("com/tampwell/staleguard/impact/ClassApiLookup"),
        )
    }

    @Test
    fun `object declarations are readable and expose their members`() {
        val api = checkNotNull(readOwn("com/tampwell/staleguard/impact/JvmDescriptors"))
        assertTrue(api.members.any { it.name == "parameterTypes" })
    }

    @Test
    fun `a non-public class contributes nothing`() {
        // Compiled from a private nested class, so it is package-private at the JVM level.
        assertNull(readOwn("com/tampwell/staleguard/impact/ClassFileApiReader\$Cursor"))
    }

    @Test
    fun `pre-Java-5 Synthetic attribute excludes a member the flag does not mark`() {
        val withAttribute = ClassFileApiReader.read(SyntheticFixture.build(withSyntheticAttribute = true))
        val without = ClassFileApiReader.read(SyntheticFixture.build(withSyntheticAttribute = false))

        assertEquals(
            "a Synthetic attribute must exclude the member exactly as the flag does",
            emptySet<MemberKey>(),
            withAttribute!!.members,
        )
        assertEquals(setOf(MemberKey("execute", "()V")), without!!.members)
    }

    @Test(expected = ClassFormatException::class)
    fun `a truncated class file fails loudly rather than reporting a smaller surface`() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/com/tampwell/staleguard/impact/MemberKey.class"))
            .use { it.readBytes() }
        ClassFileApiReader.read(bytes.copyOf(bytes.size / 2))
    }

    @Test(expected = ClassFormatException::class)
    fun `a file that is not a class file is rejected`() {
        ClassFileApiReader.read(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
    }

    /**
     * A minimal class file with one public abstract method, optionally carrying
     * the pre-Java-5 Synthetic attribute. javac 1.4 emitted exactly this shape
     * for the Miranda methods it inserted into abstract classes; a corpus run
     * over 433 jars found six jars where treating them as API produced phantom
     * removals.
     */
    private object SyntheticFixture {

        fun build(withSyntheticAttribute: Boolean): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val d = java.io.DataOutputStream(out)
            d.writeInt(0xCAFEBABE.toInt())
            d.writeShort(0) // minor
            d.writeShort(48) // major: Java 1.4, before the ACC_SYNTHETIC flag existed

            // 1 Utf8 "Fixture", 2 Class->1, 3 Utf8 "java/lang/Object", 4 Class->3,
            // 5 Utf8 "execute", 6 Utf8 "()V", 7 Utf8 "Synthetic"
            d.writeShort(8) // constant pool count = entries + 1
            writeUtf8(d, "Fixture")
            d.writeByte(7); d.writeShort(1)
            writeUtf8(d, "java/lang/Object")
            d.writeByte(7); d.writeShort(3)
            writeUtf8(d, "execute")
            writeUtf8(d, "()V")
            writeUtf8(d, "Synthetic")

            d.writeShort(0x0401) // ACC_PUBLIC | ACC_ABSTRACT
            d.writeShort(2) // this_class
            d.writeShort(4) // super_class
            d.writeShort(0) // interfaces
            d.writeShort(0) // fields

            d.writeShort(1) // methods
            d.writeShort(0x0401) // ACC_PUBLIC | ACC_ABSTRACT
            d.writeShort(5) // name
            d.writeShort(6) // descriptor
            if (withSyntheticAttribute) {
                d.writeShort(1)
                d.writeShort(7) // attribute name -> "Synthetic"
                d.writeInt(0) // zero length, as the spec requires
            } else {
                d.writeShort(0)
            }

            d.writeShort(0) // class attributes
            d.flush()
            return out.toByteArray()
        }

        private fun writeUtf8(d: java.io.DataOutputStream, value: String) {
            d.writeByte(1)
            d.writeUTF(value)
        }
    }
}
