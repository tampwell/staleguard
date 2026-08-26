package com.tampwell.staleguard.impact

/** A member's identity within one class: everything the JVM links on except the owner. */
data class MemberKey(val name: String, val descriptor: String) {
    val isMethod: Boolean get() = descriptor.startsWith("(")
    val isConstructor: Boolean get() = name == "<init>"
}

/**
 * One public or protected member of a public class, in JVM terms.
 *
 * [owner] is the internal name ("com/foo/Bar", "com/foo/Bar$Inner") and the
 * descriptor is raw, so a member's identity here is exactly the identity the
 * JVM links against at runtime. That is the point: an upgrade breaks a caller
 * when this key can no longer be resolved, whatever the source looked like.
 */
data class MemberRef(val owner: String, val key: MemberKey) {

    constructor(owner: String, name: String, descriptor: String) : this(owner, MemberKey(name, descriptor))

    val name: String get() = key.name
    val descriptor: String get() = key.descriptor
    val isMethod: Boolean get() = key.isMethod
    val isConstructor: Boolean get() = key.isConstructor

    /** Source-level class name, for display and for PSI lookup: com.foo.Bar.Inner. */
    val ownerClassName: String get() = owner.replace('/', '.').replace('$', '.')

    val ownerSimpleName: String get() = owner.substringAfterLast('/').replace('$', '.')

    /** The word to probe the index with: a constructor is written as its class name. */
    val searchWord: String get() = if (isConstructor) owner.substringAfterLast('/').substringAfterLast('$') else name

    /** Human-readable form for the report: Bar.method(String, int). */
    fun display(): String = when {
        isConstructor -> "$ownerSimpleName(${parameterList()})"
        isMethod -> "$ownerSimpleName.$name(${parameterList()})"
        else -> "$ownerSimpleName.$name"
    }

    private fun parameterList(): String =
        JvmDescriptors.parameterTypes(descriptor).joinToString(", ") { simpleName(it) }

    private fun simpleName(type: String): String {
        val base = type.substringBefore('[')
        return base.substringAfterLast('.') + type.removePrefix(base)
    }
}

/**
 * Supplies classes that are not in the jar under analysis — supertypes from
 * sibling jars on the same classpath. Kept as an interface so the diff stays
 * free of the platform and of file I/O.
 */
fun interface ClassApiLookup {
    fun find(internalName: String): ClassApi?

    companion object {
        val NONE = ClassApiLookup { null }
    }
}

/** One class's public shape, including the links needed to resolve inherited members. */
data class ClassApi(
    val internalName: String,
    val superName: String?,
    val interfaces: List<String>,
    val members: Set<MemberKey>,
    /**
     * Whether callers can name this class. Non-public classes are still kept,
     * because a public class routinely extends a package-private base that
     * declares public methods, and dropping the base would turn every one of
     * those inherited members into an unresolvable walk.
     */
    val isPublic: Boolean = true,
)

/**
 * Every member a jar exposes to callers, keyed by owning class so removals can
 * be decided the way the JVM decides them. Nothing here touches the IntelliJ
 * platform, so the analysis is testable as plain Kotlin.
 */
class ApiSurface(val classes: Map<String, ClassApi>) {

    val memberCount: Int get() = classes.values.sumOf { it.members.size }

    val isEmpty: Boolean get() = classes.isEmpty()

    /**
     * Members callable against this version that a caller could no longer link
     * against in [newer].
     *
     * Resolution walks superclasses and interfaces, because the JVM does. A
     * method pulled up into a superclass keeps working for every existing
     * caller, and a naive set difference reports the whole pull-up as breaking
     * — which is much of what a library does between majors, and would have
     * made the report worthless.
     *
     * [supertypes] supplies classes the new jar does not contain, because a
     * supertype often lives in a sibling jar (ObjectMapper extends ObjectCodec
     * from jackson-core). When a supertype cannot be resolved at all, the
     * member is NOT reported: an unproven accusation is worse here than a
     * miss, since the entire value of the feature is that its list is short
     * and every entry is real.
     */
    fun removedIn(newer: ApiSurface, supertypes: ClassApiLookup = ClassApiLookup.NONE): Set<MemberRef> {
        val removed = LinkedHashSet<MemberRef>()
        for ((internalName, old) in classes) {
            // Only classes a caller can name declare API. Members of a
            // package-private class reach callers through a public supertype,
            // where they are enumerated already.
            if (!old.isPublic) continue
            for (member in old.members) {
                if (newer.resolve(internalName, member, supertypes) == Resolution.ABSENT) {
                    removed += MemberRef(internalName, member)
                }
            }
        }
        return removed
    }

    /** Whether [member] is reachable on [internalName] here, directly or by inheritance. */
    fun resolve(
        internalName: String,
        member: MemberKey,
        supertypes: ClassApiLookup = ClassApiLookup.NONE,
    ): Resolution {
        // The owner class itself is decisive: it was in the old jar, and the
        // new jar's contents are fully known, so its absence is a fact rather
        // than the uncertainty an unresolvable supertype represents.
        val root = classes[internalName] ?: supertypes.find(internalName) ?: return Resolution.ABSENT

        val seen = HashSet<String>()
        val queue = ArrayDeque<String>()
        queue += root.internalName
        var sawUnknown = false
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current)) continue
            val info = classes[current] ?: supertypes.find(current)
            if (info == null) {
                // A JDK type never grows a library's method, so its absence
                // here is certainty rather than ignorance.
                if (!isPlatformType(current)) sawUnknown = true
                continue
            }
            if (member in info.members) return Resolution.FOUND
            info.superName?.let { queue += it }
            queue += info.interfaces
        }
        return if (sawUnknown) Resolution.UNKNOWN else Resolution.ABSENT
    }

    enum class Resolution { FOUND, ABSENT, UNKNOWN }

    companion object {
        val EMPTY = ApiSurface(emptyMap())

        fun of(classes: Collection<ClassApi>): ApiSurface =
            ApiSurface(classes.associateBy { it.internalName })

        private val PLATFORM_PREFIXES =
            listOf("java/", "javax/", "jdk/", "sun/", "com/sun/", "org/w3c/", "org/xml/", "org/ietf/")

        fun isPlatformType(internalName: String): Boolean =
            PLATFORM_PREFIXES.any { internalName.startsWith(it) }
    }
}

/** JVM descriptor parsing. Pure string work, split out so it can be tested directly. */
object JvmDescriptors {

    /**
     * Erased source-level parameter types of a method descriptor, in order:
     * "(Ljava/lang/String;I[J)V" becomes [java.lang.String, int, long[]].
     * Returns an empty list for field descriptors.
     */
    fun parameterTypes(descriptor: String): List<String> {
        if (!descriptor.startsWith("(")) return emptyList()
        val types = mutableListOf<String>()
        var i = 1
        while (i < descriptor.length && descriptor[i] != ')') {
            val start = i
            while (i < descriptor.length && descriptor[i] == '[') i++
            if (i >= descriptor.length) return types
            if (descriptor[i] == 'L') {
                val end = descriptor.indexOf(';', i)
                if (end < 0) return types
                i = end + 1
            } else {
                i++
            }
            types += sourceType(descriptor.substring(start, i))
        }
        return types
    }

    /** One descriptor type to its erased source form: "[Lcom/foo/Bar;" to "com.foo.Bar[]". */
    fun sourceType(type: String): String {
        var i = 0
        while (i < type.length && type[i] == '[') i++
        val arraySuffix = "[]".repeat(i)
        val base = when (val tag = type[i]) {
            'B' -> "byte"
            'C' -> "char"
            'D' -> "double"
            'F' -> "float"
            'I' -> "int"
            'J' -> "long"
            'S' -> "short"
            'Z' -> "boolean"
            'V' -> "void"
            'L' -> type.substring(i + 1, type.length - 1).replace('/', '.').replace('$', '.')
            else -> tag.toString()
        }
        return base + arraySuffix
    }
}
