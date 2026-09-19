package com.tampwell.staleguard.reach

import com.tampwell.staleguard.impact.ClassFormatException
import com.tampwell.staleguard.impact.MemberKey
import java.nio.charset.StandardCharsets

/** How the JVM picks the method a call site actually runs. */
enum class Dispatch {
    /** invokestatic: exactly the resolved method, no receiver. */
    STATIC,

    /** invokespecial: constructors, super calls, pre-Java-11 private calls. Exactly the resolved method. */
    SPECIAL,

    /** invokevirtual / invokeinterface: selected from the receiver's runtime class. */
    VIRTUAL,
}

/** One call a method body can make, exactly as the bytecode names it. */
data class CallSite(val owner: String, val key: MemberKey, val dispatch: Dispatch)

/** What one method body can do to the rest of the program. */
class MethodBody(
    val access: Int,
    val calls: Set<CallSite>,
    /** Classes whose static initializer this body can trigger: new, static field access, static calls. */
    val initializes: Set<String>,
    /** Classes this body creates instances of: new and constructor references. */
    val instantiates: Set<String>,
) {
    val isStatic: Boolean get() = access and ACC_STATIC != 0
    val isPrivate: Boolean get() = access and ACC_PRIVATE != 0
    val isAbstract: Boolean get() = access and ACC_ABSTRACT != 0

    internal companion object {
        const val ACC_PRIVATE = 0x0002
        const val ACC_STATIC = 0x0008
        const val ACC_ABSTRACT = 0x0400
    }
}

/** A class's place in the hierarchy, without its bodies. */
class ClassHeader(
    val internalName: String,
    val access: Int,
    val superName: String?,
    val interfaces: List<String>,
) {
    val isInterface: Boolean get() = access and ACC_INTERFACE != 0
    val isAbstract: Boolean get() = access and ACC_ABSTRACT != 0

    internal companion object {
        const val ACC_INTERFACE = 0x0200
        const val ACC_ABSTRACT = 0x0400
    }
}

class ClassBodies(val header: ClassHeader, val methods: Map<MemberKey, MethodBody>)

/**
 * Reads the outgoing edges of every method in a class file: the calls each
 * body makes, the classes it instantiates, and the static initializers it can
 * trigger. The input to reachability, and deliberately not ASM for the same
 * classloader reasons [com.tampwell.staleguard.impact.ClassFileApiReader]
 * documents; a differential test holds this reader to the platform's ASM over
 * real jars instead.
 *
 * Calls hidden behind invokedynamic are followed through the class's
 * BootstrapMethods table: a lambda's body and a method reference's target are
 * method-handle arguments of the bootstrap, and a reader that stopped at the
 * indy instruction would report every lambda-routed path as unreachable. The
 * bootstrap method itself is recorded too, because it runs at link time.
 */
object ClassBodyReader {

    private const val MAGIC = 0xCAFEBABEL
    private const val ACC_SYNTHETIC = 0x1000

    /** Header only: the constant pool is still read, the member tables are not. */
    fun readHeader(data: ByteArray, intern: (String) -> String = { it }): ClassHeader {
        val cursor = Cursor(data)
        val pool = readPool(cursor, intern)
        return readHeaderAfterPool(cursor, pool)
    }

    fun read(data: ByteArray, intern: (String) -> String = { it }): ClassBodies {
        val cursor = Cursor(data)
        val pool = readPool(cursor, intern)
        val header = readHeaderAfterPool(cursor, pool)

        skipMembers(cursor) // fields

        class Pending(val access: Int, val builder: BodyBuilder)
        val pending = LinkedHashMap<MemberKey, Pending>()
        repeat(cursor.u2()) {
            var access = cursor.u2()
            val name = pool.utf8(cursor.u2())
            val descriptor = pool.utf8(cursor.u2())
            val builder = BodyBuilder()
            repeat(cursor.u2()) {
                val attributeName = pool.utf8(cursor.u2())
                val length = cursor.u4()
                val end = cursor.position + length
                when (attributeName) {
                    "Code" -> scanCode(cursor, pool, builder)
                    // Before class file version 49 synthetic-ness was an
                    // attribute, not a flag; the JVM treats them the same.
                    "Synthetic" -> access = access or ACC_SYNTHETIC
                }
                cursor.seek(end)
            }
            pending[MemberKey(name, descriptor)] = Pending(access, builder)
        }

        var bootstraps: List<Bootstrap> = emptyList()
        repeat(cursor.u2()) {
            val attributeName = pool.utf8(cursor.u2())
            val length = cursor.u4()
            val end = cursor.position + length
            if (attributeName == "BootstrapMethods") {
                bootstraps = List(cursor.u2()) {
                    val methodRef = cursor.u2()
                    Bootstrap(methodRef, IntArray(cursor.u2()) { cursor.u2() })
                }
            }
            cursor.seek(end)
        }

        val methods = LinkedHashMap<MemberKey, MethodBody>(pending.size)
        for ((key, entry) in pending) {
            val builder = entry.builder
            for (index in builder.bootstrapIndices) {
                expandBootstrap(index, bootstraps, pool, builder, HashSet())
            }
            methods[key] = MethodBody(entry.access, builder.calls, builder.initializes, builder.instantiates)
        }
        return ClassBodies(header, methods)
    }

    private class Bootstrap(val methodRef: Int, val arguments: IntArray)

    private class BodyBuilder {
        val calls = LinkedHashSet<CallSite>()
        val initializes = LinkedHashSet<String>()
        val instantiates = LinkedHashSet<String>()
        val bootstrapIndices = LinkedHashSet<Int>()
    }

    private fun expandBootstrap(
        index: Int,
        bootstraps: List<Bootstrap>,
        pool: Pool,
        builder: BodyBuilder,
        seen: MutableSet<Int>,
    ) {
        if (!seen.add(index)) return
        val bootstrap = bootstraps.getOrNull(index)
            ?: throw ClassFormatException("bootstrap method $index missing")
        recordHandle(bootstrap.methodRef, pool, builder)
        for (argument in bootstrap.arguments) {
            when (pool.tag(argument)) {
                TAG_METHOD_HANDLE -> recordHandle(argument, pool, builder)
                // A dynamic constant argument runs its own bootstrap.
                TAG_DYNAMIC -> expandBootstrap(pool.a(argument), bootstraps, pool, builder, seen)
            }
        }
    }

    private fun recordHandle(handleIndex: Int, pool: Pool, builder: BodyBuilder) {
        if (pool.tag(handleIndex) != TAG_METHOD_HANDLE) {
            throw ClassFormatException("constant $handleIndex is not a method handle")
        }
        val kind = pool.a(handleIndex)
        val member = pool.memberRef(pool.b(handleIndex))
        when (kind) {
            REF_GET_STATIC, REF_PUT_STATIC -> builder.initializes += member.owner
            REF_GET_FIELD, REF_PUT_FIELD -> Unit
            REF_INVOKE_VIRTUAL, REF_INVOKE_INTERFACE ->
                builder.calls += CallSite(member.owner, member.key, Dispatch.VIRTUAL)
            REF_INVOKE_STATIC -> {
                builder.calls += CallSite(member.owner, member.key, Dispatch.STATIC)
                builder.initializes += member.owner
            }
            REF_INVOKE_SPECIAL -> builder.calls += CallSite(member.owner, member.key, Dispatch.SPECIAL)
            REF_NEW_INVOKE_SPECIAL -> {
                builder.calls += CallSite(member.owner, member.key, Dispatch.SPECIAL)
                builder.instantiates += member.owner
                builder.initializes += member.owner
            }
            else -> throw ClassFormatException("unknown method handle kind $kind")
        }
    }

    private fun scanCode(cursor: Cursor, pool: Pool, builder: BodyBuilder) {
        cursor.u2() // max_stack
        cursor.u2() // max_locals
        val codeLength = cursor.u4()
        val codeStart = cursor.position
        val code = cursor.bytes
        val codeEnd = codeStart + codeLength
        if (codeLength < 0 || codeEnd > code.size) throw ClassFormatException("code overruns the class file")

        fun u1(at: Int): Int {
            if (at >= codeEnd) throw ClassFormatException("instruction overruns its code")
            return code[at].toInt() and 0xFF
        }
        fun u2(at: Int): Int = (u1(at) shl 8) or u1(at + 1)
        fun s4(at: Int): Int = (u2(at) shl 16) or u2(at + 2)

        var pc = codeStart
        while (pc < codeEnd) {
            val opcode = u1(pc)
            val length = when (opcode) {
                OP_TABLESWITCH -> {
                    // Operands are 4-byte aligned relative to the start of the code array.
                    val operands = pc + 1 + padding(pc - codeStart)
                    val low = s4(operands + 4)
                    val high = s4(operands + 8)
                    val count = high.toLong() - low + 1
                    if (count < 0 || count > codeLength) throw ClassFormatException("bad tableswitch range")
                    (operands - pc) + 12 + (4 * count).toInt()
                }
                OP_LOOKUPSWITCH -> {
                    val operands = pc + 1 + padding(pc - codeStart)
                    val pairs = s4(operands + 4)
                    if (pairs < 0 || pairs > codeLength) throw ClassFormatException("bad lookupswitch size")
                    (operands - pc) + 8 + 8 * pairs
                }
                OP_WIDE -> if (u1(pc + 1) == OP_IINC) 6 else 4
                else -> OPCODE_LENGTH[opcode].takeIf { it > 0 }
                    ?: throw ClassFormatException("undefined opcode $opcode")
            }
            if (pc + length > codeEnd) throw ClassFormatException("instruction overruns its code")

            when (opcode) {
                OP_INVOKEVIRTUAL, OP_INVOKEINTERFACE -> pool.memberRef(u2(pc + 1)).let {
                    builder.calls += CallSite(it.owner, it.key, Dispatch.VIRTUAL)
                }
                OP_INVOKESPECIAL -> pool.memberRef(u2(pc + 1)).let {
                    builder.calls += CallSite(it.owner, it.key, Dispatch.SPECIAL)
                }
                OP_INVOKESTATIC -> pool.memberRef(u2(pc + 1)).let {
                    builder.calls += CallSite(it.owner, it.key, Dispatch.STATIC)
                    builder.initializes += it.owner
                }
                OP_GETSTATIC, OP_PUTSTATIC -> builder.initializes += pool.memberRef(u2(pc + 1)).owner
                OP_NEW -> pool.className(u2(pc + 1)).let {
                    builder.instantiates += it
                    builder.initializes += it
                }
                OP_INVOKEDYNAMIC -> {
                    val index = u2(pc + 1)
                    if (pool.tag(index) != TAG_INVOKE_DYNAMIC) throw ClassFormatException("invokedynamic without its constant")
                    builder.bootstrapIndices += pool.a(index)
                }
                OP_LDC, OP_LDC_W -> {
                    val index = if (opcode == OP_LDC) u1(pc + 1) else u2(pc + 1)
                    when (pool.tag(index)) {
                        TAG_METHOD_HANDLE -> recordHandle(index, pool, builder)
                        TAG_DYNAMIC -> builder.bootstrapIndices += pool.a(index)
                    }
                }
            }
            pc += length
        }
    }

    private fun padding(offsetInCode: Int): Int = (4 - ((offsetInCode + 1) % 4)) % 4

    private fun readHeaderAfterPool(cursor: Cursor, pool: Pool): ClassHeader {
        val access = cursor.u2()
        val name = pool.className(cursor.u2())
        // java/lang/Object has super_class 0; every other class names one.
        val superName = cursor.u2().takeIf { it != 0 }?.let { pool.className(it) }
        val interfaces = List(cursor.u2()) { pool.className(cursor.u2()) }
        return ClassHeader(name, access, superName, interfaces)
    }

    private fun skipMembers(cursor: Cursor) {
        repeat(cursor.u2()) {
            cursor.skip(6) // access, name, descriptor
            repeat(cursor.u2()) {
                cursor.skip(2)
                cursor.skip(cursor.u4())
            }
        }
    }

    private class Member(val owner: String, val key: MemberKey)

    private class Pool(
        private val tags: IntArray,
        private val strings: Array<String?>,
        private val first: IntArray,
        private val second: IntArray,
    ) {
        private fun check(index: Int) {
            if (index <= 0 || index >= tags.size) throw ClassFormatException("constant index $index out of range")
        }

        fun tag(index: Int): Int {
            check(index)
            return tags[index]
        }

        fun a(index: Int): Int {
            check(index)
            return first[index]
        }

        fun b(index: Int): Int {
            check(index)
            return second[index]
        }

        fun utf8(index: Int): String {
            check(index)
            return strings[index] ?: throw ClassFormatException("constant $index is not UTF8")
        }

        fun className(index: Int): String {
            if (tag(index) != TAG_CLASS) throw ClassFormatException("constant $index is not a class")
            return utf8(first[index])
        }

        fun memberRef(index: Int): Member {
            when (tag(index)) {
                TAG_FIELDREF, TAG_METHODREF, TAG_INTERFACE_METHODREF -> Unit
                else -> throw ClassFormatException("constant $index is not a member reference")
            }
            val nat = second[index]
            if (tag(nat) != TAG_NAME_AND_TYPE) throw ClassFormatException("constant $nat is not a name-and-type")
            return Member(className(first[index]), MemberKey(utf8(first[nat]), utf8(second[nat])))
        }
    }

    private fun readPool(cursor: Cursor, intern: (String) -> String): Pool {
        // Read as two halves: the magic exceeds Int.MAX_VALUE, which u4() rejects as a length.
        val magic = (cursor.u2().toLong() shl 16) or cursor.u2().toLong()
        if (magic != MAGIC) throw ClassFormatException("not a class file")
        cursor.u2() // minor
        cursor.u2() // major
        val count = cursor.u2()
        val tags = IntArray(count)
        val strings = arrayOfNulls<String>(count)
        val first = IntArray(count)
        val second = IntArray(count)
        var i = 1
        while (i < count) {
            val tag = cursor.u1()
            tags[i] = tag
            when (tag) {
                TAG_UTF8 -> strings[i] = intern(cursor.utf8())
                TAG_CLASS -> first[i] = cursor.u2()
                TAG_FIELDREF, TAG_METHODREF, TAG_INTERFACE_METHODREF, TAG_NAME_AND_TYPE,
                TAG_DYNAMIC, TAG_INVOKE_DYNAMIC -> {
                    first[i] = cursor.u2()
                    second[i] = cursor.u2()
                }
                TAG_METHOD_HANDLE -> {
                    first[i] = cursor.u1()
                    second[i] = cursor.u2()
                }
                TAG_STRING, TAG_METHOD_TYPE, TAG_MODULE, TAG_PACKAGE -> cursor.skip(2)
                TAG_INTEGER, TAG_FLOAT -> cursor.skip(4)
                // Long and Double take two constant pool slots.
                TAG_LONG, TAG_DOUBLE -> {
                    cursor.skip(8)
                    i++
                }
                else -> throw ClassFormatException("unknown constant pool tag $tag")
            }
            i++
        }
        return Pool(tags, strings, first, second)
    }

    private class Cursor(val bytes: ByteArray) {
        var position = 0
            private set

        fun u1(): Int {
            require(1)
            return bytes[position++].toInt() and 0xFF
        }

        fun u2(): Int {
            require(2)
            return ((bytes[position++].toInt() and 0xFF) shl 8) or (bytes[position++].toInt() and 0xFF)
        }

        /** Attribute and code lengths: u4 on disk, but nothing in a real class file approaches 2 GB. */
        fun u4(): Int {
            val value = (u2().toLong() shl 16) or u2().toLong()
            if (value > Int.MAX_VALUE) throw ClassFormatException("length $value too large")
            return value.toInt()
        }

        fun utf8(): String {
            val length = u2()
            require(length)
            // Modified UTF-8 differs from standard UTF-8 only for NUL and
            // supplementary characters, neither of which appears in a name or
            // descriptor anything can call.
            return String(bytes, position, length, StandardCharsets.UTF_8).also { position += length }
        }

        fun skip(n: Int) {
            if (n < 0) throw ClassFormatException("negative length $n")
            require(n)
            position += n
        }

        fun seek(target: Int) {
            if (target < position || target > bytes.size) throw ClassFormatException("attribute length mismatch")
            position = target
        }

        private fun require(n: Int) {
            if (position + n > bytes.size) throw ClassFormatException("truncated class file")
        }
    }

    private val OPCODE_LENGTH = IntArray(256).also { t ->
        for (op in 0x00..0x0F) t[op] = 1 // nop, constants
        t[0x10] = 2 // bipush
        t[0x11] = 3 // sipush
        t[0x12] = 2 // ldc
        t[0x13] = 3 // ldc_w
        t[0x14] = 3 // ldc2_w
        for (op in 0x15..0x19) t[op] = 2 // xload index
        for (op in 0x1A..0x35) t[op] = 1 // xload_n, array loads
        for (op in 0x36..0x3A) t[op] = 2 // xstore index
        for (op in 0x3B..0x83) t[op] = 1 // xstore_n, array stores, stack ops, arithmetic
        t[0x84] = 3 // iinc
        for (op in 0x85..0x98) t[op] = 1 // conversions, comparisons
        for (op in 0x99..0xA8) t[op] = 3 // conditional branches, goto, jsr
        t[0xA9] = 2 // ret
        for (op in 0xAC..0xB1) t[op] = 1 // returns
        for (op in 0xB2..0xB8) t[op] = 3 // field access, invokevirtual/special/static
        t[0xB9] = 5 // invokeinterface
        t[0xBA] = 5 // invokedynamic
        t[0xBB] = 3 // new
        t[0xBC] = 2 // newarray
        t[0xBD] = 3 // anewarray
        t[0xBE] = 1 // arraylength
        t[0xBF] = 1 // athrow
        t[0xC0] = 3 // checkcast
        t[0xC1] = 3 // instanceof
        t[0xC2] = 1 // monitorenter
        t[0xC3] = 1 // monitorexit
        t[0xC5] = 4 // multianewarray
        t[0xC6] = 3 // ifnull
        t[0xC7] = 3 // ifnonnull
        t[0xC8] = 5 // goto_w
        t[0xC9] = 5 // jsr_w
        // 0xAA tableswitch, 0xAB lookupswitch, 0xC4 wide: variable, handled inline.
    }

    private const val OP_LDC = 0x12
    private const val OP_LDC_W = 0x13
    private const val OP_IINC = 0x84
    private const val OP_TABLESWITCH = 0xAA
    private const val OP_LOOKUPSWITCH = 0xAB
    private const val OP_GETSTATIC = 0xB2
    private const val OP_PUTSTATIC = 0xB3
    private const val OP_INVOKEVIRTUAL = 0xB6
    private const val OP_INVOKESPECIAL = 0xB7
    private const val OP_INVOKESTATIC = 0xB8
    private const val OP_INVOKEINTERFACE = 0xB9
    private const val OP_INVOKEDYNAMIC = 0xBA
    private const val OP_NEW = 0xBB
    private const val OP_WIDE = 0xC4

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

    private const val REF_GET_FIELD = 1
    private const val REF_GET_STATIC = 2
    private const val REF_PUT_FIELD = 3
    private const val REF_PUT_STATIC = 4
    private const val REF_INVOKE_VIRTUAL = 5
    private const val REF_INVOKE_STATIC = 6
    private const val REF_INVOKE_SPECIAL = 7
    private const val REF_NEW_INVOKE_SPECIAL = 8
    private const val REF_INVOKE_INTERFACE = 9
}
