package com.tampwell.staleguard.impact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The linkage scan, exercised against this module's own compiled classes so
 * the fixtures are real output of the toolchain that builds the plugin.
 */
class ClassScanTest {

    private fun scanOwn(internalName: String): ClassScan {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/$internalName.class")) {
            "compiled class not on the test classpath: $internalName"
        }.use { it.readBytes() }
        return ClassFileApiReader.scan(bytes)
    }

    @Test
    fun `refs list the members a class actually calls`() {
        // ClasspathClassLookup.find calls ClassFileApiReader.read — that call
        // must appear in its constant pool.
        val scan = scanOwn("com/tampwell/staleguard/impact/ClasspathClassLookup")

        assertTrue(
            "expected a ref to ClassFileApiReader.read, got ${scan.refs.filter { it.owner.contains("ClassFile") }}",
            scan.refs.any { it.owner == "com/tampwell/staleguard/impact/ClassFileApiReader" && it.name == "read" },
        )
    }

    @Test
    fun `refs to the JDK are captured too`() {
        val scan = scanOwn("com/tampwell/staleguard/impact/JarApiReader")

        assertTrue(scan.refs.any { it.owner.startsWith("java/") })
    }

    @Test
    fun `array pseudo-owners never appear as refs`() {
        // Every class in the module: an owner starting with '[' resolves
        // against Object at runtime, never against a jar.
        for (name in listOf(
            "com/tampwell/staleguard/impact/JarApiReader",
            "com/tampwell/staleguard/impact/ClasspathClassLookup",
            "com/tampwell/staleguard/impact/JvmDescriptors",
        )) {
            assertTrue(scanOwn(name).refs.none { it.owner.startsWith("[") })
        }
    }

    @Test
    fun `declaredAll includes private members that the api view excludes`() {
        val scan = scanOwn("com/tampwell/staleguard/impact/ClasspathClassLookup")

        // find() is public: in both. The backing 'open'/'parsed' field
        // accessors are private machinery: declared, not API.
        assertTrue(scan.declaredAll.containsAll(scan.api.members))
        assertTrue(
            "expected private declarations beyond the public API",
            scan.declaredAll.size > scan.api.members.size,
        )
    }

    @Test
    fun `the api view of scan matches read exactly`() {
        for (name in listOf(
            "com/tampwell/staleguard/impact/MemberKey",
            "com/tampwell/staleguard/impact/JarApiReader",
            "com/tampwell/staleguard/impact/ClassFileApiReader",
        )) {
            val bytes = checkNotNull(javaClass.getResourceAsStream("/$name.class")).use { it.readBytes() }
            assertEquals("read and scan disagree for $name", ClassFileApiReader.read(bytes), ClassFileApiReader.scan(bytes).api)
        }
    }

    @Test
    fun `a class does not reference itself as an external unresolvable`() {
        // Self-refs exist in the pool (private helpers) and must resolve
        // against declaredAll — this is why declaredAll includes private.
        val scan = scanOwn("com/tampwell/staleguard/impact/JvmDescriptors")
        val selfRefs = scan.refs.filter { it.owner == scan.internalName }

        for (ref in selfRefs) {
            assertTrue("self-ref ${ref.name}${ref.descriptor} missing from declaredAll", ref.key in scan.declaredAll)
        }
    }

    @Test(expected = ClassFormatException::class)
    fun `scan fails loudly on truncated input, same as read`() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/com/tampwell/staleguard/impact/MemberKey.class"))
            .use { it.readBytes() }
        ClassFileApiReader.scan(bytes.copyOf(bytes.size / 3))
    }

    @Test
    fun `every ref descriptor is well-formed`() {
        val scan = scanOwn("com/tampwell/staleguard/impact/ClassFileApiReader")

        for (ref in scan.refs) {
            assertFalse(ref.owner.isEmpty())
            assertFalse(ref.name.isEmpty())
            assertTrue(
                "odd descriptor ${ref.descriptor} on ${ref.owner}#${ref.name}",
                ref.descriptor.startsWith("(") || ref.descriptor.first() in "BCDFIJSZL[",
            )
        }
    }
}
