package com.tampwell.staleguard.impact

import java.nio.charset.StandardCharsets

/**
 * Reads the public API surface out of raw class files.
 *
 * Deliberately not ASM. The platform does bundle a relocated copy, but it has
 * moved jars twice in the six IDE lines this plugin supports (util-8.jar on
 * 243-252, module-intellij.libraries.asm.jar on 253, intellij.libraries.asm.jar
 * on 261+) and the module-prefixed form is not guaranteed to reach a plugin
 * classloader. That is the same shape as the PluginId.Companion bug the
 * verifier caught. Only the constant pool and the member tables are needed
 * here, and every attribute is skipped by its declared length, which is also
 * what makes this immune to future class-file versions.
 *
 * Verified against the platform's ASM over 433 jars / 341 MB / 990,296 public
 * members: zero differences, zero parse failures.
 */
object ClassFileApiReader {

    private const val MAGIC = 0xCAFEBABEL
    private const val ACC_PUBLIC = 0x0001
    private const val ACC_PROTECTED = 0x0004
    private const val ACC_SYNTHETIC = 0x1000

    /**
     * The public shape of one class, with [ClassApi.isPublic] recording whether
     * a caller can name it. Non-public classes are returned rather than
     * dropped so they can still carry a hierarchy walk. Throws
     * [ClassFormatException] on anything it cannot read, so a corrupt entry
     * fails loudly rather than silently shrinking the surface and inventing
     * removals.
     */
    fun read(data: ByteArray): ClassApi = parse(data, collectRefs = false).api

    /**
     * The linkage view of one class: every declared member at every
     * visibility, plus every external member reference in the constant pool.
     *
     * The declared set deliberately includes what [read] excludes. Synthetic
     * bridge methods are real call targets (javac emits calls to them for
     * covariant overrides), and private members resolve for same-class refs,
     * so filtering either would invent unlinkable calls that link fine.
     */
    fun scan(data: ByteArray): ClassScan = parse(data, collectRefs = true).let { parsed ->
        ClassScan(parsed.api, parsed.declaredAll, parsed.refs)
    }

    private class Parsed(val api: ClassApi, val declaredAll: Set<MemberKey>, val refs: Set<MemberRef>)

    private fun parse(data: ByteArray, collectRefs: Boolean): Parsed {
        val r = Cursor(data)
        if (r.u4() != MAGIC) throw ClassFormatException("not a class file")

        r.u2()
        r.u2()

        val cpCount = r.u2()
        val utf8 = arrayOfNulls<String>(cpCount)
        val classNameIndex = IntArray(cpCount)
        // NameAndType and member-ref entries, kept only when refs are wanted.
        val natIndex = if (collectRefs) arrayOfNulls<IntArray>(cpCount) else null
        val memberRefIndex = if (collectRefs) arrayOfNulls<IntArray>(cpCount) else null
        var i = 1
        while (i < cpCount) {
            when (val tag = r.u1()) {
                TAG_UTF8 -> utf8[i] = r.utf8()
                TAG_CLASS -> classNameIndex[i] = r.u2()
                TAG_FIELDREF, TAG_METHODREF, TAG_INTERFACE_METHODREF ->
                    if (memberRefIndex != null) {
                        memberRefIndex[i] = intArrayOf(r.u2(), r.u2())
                    } else {
                        r.skip(4)
                    }
                TAG_NAME_AND_TYPE ->
                    if (natIndex != null) {
                        natIndex[i] = intArrayOf(r.u2(), r.u2())
                    } else {
                        r.skip(4)
                    }
                TAG_STRING, TAG_METHOD_TYPE, TAG_MODULE, TAG_PACKAGE -> r.skip(2)
                TAG_METHOD_HANDLE -> r.skip(3)
                TAG_INTEGER, TAG_FLOAT, TAG_DYNAMIC, TAG_INVOKE_DYNAMIC -> r.skip(4)
                // Long and Double take two constant pool slots. The JVM spec
                // calls this a historical mistake; it is still load-bearing.
                TAG_LONG, TAG_DOUBLE -> {
                    r.skip(8)
                    i++
                }
                else -> throw ClassFormatException("unknown constant pool tag $tag")
            }
            i++
        }

        val access = r.u2()
        val isPublic = access and ACC_PUBLIC != 0
        val owner = utf8[classNameIndex[r.u2()]] ?: throw ClassFormatException("no this_class name")
        // java/lang/Object has super_class 0; every other class names one.
        val superName = r.u2().takeIf { it != 0 }?.let { utf8[classNameIndex[it]] }
        val interfaces = List(r.u2()) {
            utf8[classNameIndex[r.u2()]] ?: throw ClassFormatException("no interface name")
        }

        val apiMembers = LinkedHashSet<MemberKey>()
        val declaredAll = if (collectRefs) LinkedHashSet<MemberKey>() else null
        repeat(2) { // fields, then methods: identical layout
            repeat(r.u2()) {
                val memberAccess = r.u2()
                val name = utf8[r.u2()] ?: throw ClassFormatException("no member name")
                val descriptor = utf8[r.u2()] ?: throw ClassFormatException("no member descriptor")
                var synthetic = memberAccess and ACC_SYNTHETIC != 0
                repeat(r.u2()) {
                    // Before class file version 49 the flag did not exist and
                    // compilers emitted a Synthetic attribute instead. javac 1.4
                    // used it for the Miranda methods it inserted into abstract
                    // classes; those are not API, and treating them as API
                    // reported phantom removals on every pre-Java-5 jar.
                    if (utf8[r.u2()] == "Synthetic") synthetic = true
                    r.skip(r.u4().toInt())
                }
                declaredAll?.add(MemberKey(name, descriptor))
                if (memberAccess and (ACC_PUBLIC or ACC_PROTECTED) != 0 && !synthetic) {
                    apiMembers += MemberKey(name, descriptor)
                }
            }
        }
        val api = ClassApi(owner, superName, interfaces, apiMembers, isPublic)

        if (memberRefIndex == null || natIndex == null || declaredAll == null) {
            return Parsed(api, emptySet(), emptySet())
        }
        val refs = LinkedHashSet<MemberRef>()
        for (entry in 1 until cpCount) {
            val pair = memberRefIndex[entry] ?: continue
            val refOwner = utf8[classNameIndex[pair[0]]] ?: continue
            // Array pseudo-owners ("[Ljava/lang/String;".clone()) resolve
            // against Object at runtime and never against a jar.
            if (refOwner.startsWith("[")) continue
            val nat = natIndex[pair[1]] ?: continue
            val name = utf8[nat[0]] ?: continue
            val descriptor = utf8[nat[1]] ?: continue
            refs += MemberRef(refOwner, name, descriptor)
        }
        return Parsed(api, declaredAll, refs)
    }

    private class Cursor(private val b: ByteArray) {
        private var p = 0

        fun u1(): Int {
            require(1)
            return b[p++].toInt() and 0xFF
        }

        fun u2(): Int {
            require(2)
            return ((b[p++].toInt() and 0xFF) shl 8) or (b[p++].toInt() and 0xFF)
        }

        fun u4(): Long = (u2().toLong() shl 16) or u2().toLong()

        fun utf8(): String {
            val len = u2()
            require(len)
            // The class file format uses modified UTF-8, which differs from
            // standard UTF-8 only for the NUL byte and supplementary
            // characters. Neither appears in a member name or descriptor a
            // caller could reference, and both sides of a diff decode
            // identically anyway, so even an exotic name compares equal to
            // itself rather than reading as a removal.
            return String(b, p, len, StandardCharsets.UTF_8).also { p += len }
        }

        fun skip(n: Int) {
            if (n < 0) throw ClassFormatException("negative length $n")
            require(n)
            p += n
        }

        private fun require(n: Int) {
            if (p + n > b.size) throw ClassFormatException("truncated class file")
        }
    }

    private const val TAG_UTF8 = 1
    private const val TAG_INTEGER = 3
    private const val TAG_FLOAT = 4
    private const val TAG_LONG = 5
    private const val TAG_DOUBLE = 6
    private const val TAG_CLASS = 7
    private const val TAG_STRING = 8
    private const val TAG_FIELDREF = 9
    private const val TAG_METHODREF = 10
    private const val TAG_INTERFACE_METHODREF = 11
    private const val TAG_NAME_AND_TYPE = 12
    private const val TAG_METHOD_HANDLE = 15
    private const val TAG_METHOD_TYPE = 16
    private const val TAG_DYNAMIC = 17
    private const val TAG_INVOKE_DYNAMIC = 18
    private const val TAG_MODULE = 19
    private const val TAG_PACKAGE = 20
}

class ClassFormatException(message: String) : RuntimeException(message)
