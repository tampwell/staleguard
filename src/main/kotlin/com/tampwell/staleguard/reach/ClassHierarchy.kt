package com.tampwell.staleguard.reach

import com.tampwell.staleguard.impact.MemberKey
import com.tampwell.staleguard.impact.MemberRef

/**
 * The type world one analysis runs in: every class on the classpath by
 * header, eagerly, because dispatch needs to enumerate subtypes; bodies
 * lazily, because only reached classes need them; JDK classes on demand,
 * method tables only, because JDK bodies are never walked.
 *
 * Resolution and selection follow JVMS 5.4.3.3 and 5.4.6 rather than a
 * source-level approximation, so a path reported through a default method or
 * an inherited implementation is a path the JVM would actually take.
 */
internal class ClassHierarchy(
    private val source: ClassSource,
    private val platformBytes: (String) -> ByteArray?,
) {
    private val canonical = ClassBodyReader.Canonical()

    private val headers = HashMap<String, ClassHeader>()
    private val directSubtypes = HashMap<String, MutableList<String>>()

    /** Classes on the classpath whose bytes could not be read. Anything behind them is unseen. */
    val unreadable = ArrayList<String>()

    /** Every class some code on the classpath creates with new or a constructor reference. */
    private val createdSomewhere = HashSet<String>()

    /** Local and anonymous classes, including compiler-generated lambda and coroutine classes. */
    private val localClasses = HashSet<String>()

    init {
        // Bodies are scanned here only to learn what code creates, then
        // dropped: holding every body of a classpath is the walk's job, and
        // only for the classes it reaches. Hence a canonicalizer of its own,
        // garbage once indexing ends, instead of the walk's, which would
        // otherwise retain every call site on the classpath.
        val indexing = ClassBodyReader.Canonical()
        for (name in source.classNames) {
            val parsed = source.bytes(name)
                ?.let { runCatching { ClassBodyReader.read(it, indexing) }.getOrNull() }
            val header = parsed?.header
            // A header naming a different class than its entry is a mislabelled
            // file the JVM would refuse to define; it cannot be on any path.
            if (header == null || header.internalName != name) {
                unreadable += name
                continue
            }
            headers[header.internalName] = header
            header.superName?.let { directSubtypes.getOrPut(it) { ArrayList() } += header.internalName }
            for (parent in header.interfaces) directSubtypes.getOrPut(parent) { ArrayList() } += header.internalName
            for (body in parsed.methods.values) createdSomewhere.addAll(body.instantiates)
            if (parsed.isLocalOrAnonymous) localClasses += header.internalName
        }
    }

    fun isLocalOrAnonymous(name: String): Boolean = name in localClasses

    /**
     * False when no code anywhere creates [name]: its instances can only come
     * from reflection, deserialization, a proxy or the runtime itself, so a
     * sound walk must assume they exist.
     */
    fun isCreatedInCode(name: String): Boolean = name in createdSomewhere

    val classpathClasses: Collection<String> get() = headers.keys

    fun isClasspath(name: String): Boolean = name in headers

    fun isPlatform(name: String): Boolean = name !in headers && platformInfo(name) != null

    private val bodies = HashMap<String, ClassBodies>()
    private val platform = HashMap<String, ClassBodies?>()

    /** Declarations and bodies of a classpath class, or declarations only for a JDK class. */
    fun info(name: String): ClassBodies? {
        if (name in headers) {
            bodies[name]?.let { return it }
            val parsed = source.bytes(name)?.let { runCatching { ClassBodyReader.read(it, canonical) }.getOrNull() }
            if (parsed == null) {
                if (name !in unreadable) unreadable += name
                return null
            }
            bodies[name] = parsed
            return parsed
        }
        return platformInfo(name)
    }

    private fun platformInfo(name: String): ClassBodies? {
        if (name.startsWith("[")) return null
        if (platform.containsKey(name)) return platform[name]
        val parsed = platformBytes(name)
            ?.let { runCatching { ClassBodyReader.read(it, canonical, scanBodies = false) }.getOrNull() }
        platform[name] = parsed
        return parsed
    }

    private val ancestorCache = HashMap<String, Set<String>>()

    /** Every supertype, classes and interfaces, JDK types included; unknown supertypes end the walk. */
    fun ancestors(name: String): Set<String> {
        ancestorCache[name]?.let { return it }
        val result = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        info(name)?.header?.let { header ->
            header.superName?.let(queue::add)
            queue.addAll(header.interfaces)
        }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!result.add(current)) continue
            val header = info(current)?.header ?: continue
            header.superName?.let(queue::add)
            queue.addAll(header.interfaces)
        }
        ancestorCache[name] = result
        return result
    }

    private val subtypeCache = HashMap<String, List<String>>()

    /** Every classpath class below [name], transitively. */
    fun subtypes(name: String): List<String> {
        subtypeCache[name]?.let { return it }
        val result = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        directSubtypes[name]?.let(queue::addAll)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!result.add(current)) continue
            directSubtypes[current]?.let(queue::addAll)
        }
        return result.toList().also { subtypeCache[name] = it }
    }

    fun isConcrete(name: String): Boolean {
        val header = headers[name] ?: return false
        return !header.isInterface && !header.isAbstract
    }

    fun body(method: MemberRef): MethodBody? = info(method.owner)?.methods?.get(method.key)

    /**
     * JVMS 5.4.3.3 method resolution: the owner, its superclass chain, then
     * the maximally specific superinterface declarations. Several defaults
     * that the JVM would call ambiguous all come back; a reachability answer
     * that picked one arbitrarily could hide the path through the other.
     * Empty when the chain reaches a class nobody can load.
     */
    fun resolve(owner: String, key: MemberKey): List<MemberRef> {
        if (owner.startsWith("[")) return emptyList()
        var current: String? = owner
        val seen = HashSet<String>()
        while (current != null && seen.add(current)) {
            val info = info(current) ?: return emptyList()
            if (key in info.methods) return listOf(MemberRef(current, key))
            current = info.header.superName
        }
        val declaring = maximallySpecific(owner, key)
        val concrete = declaring.filter { body(it)?.isAbstract == false }
        return concrete.ifEmpty { declaring }
    }

    /**
     * JVMS 5.4.6 selection: what a virtual call runs when the receiver's
     * class is exactly [runtimeClass]. The superclass chain's first concrete
     * instance declaration wins, then maximally specific defaults. Not
     * cached: each call site expands once, and a cache keyed by every
     * (receiver, method) pair a dispatch touches outweighed the work it saved.
     */
    fun select(runtimeClass: String, key: MemberKey): List<MemberRef> {
        var current: String? = runtimeClass
        val seen = HashSet<String>()
        while (current != null && seen.add(current)) {
            val info = info(current) ?: return emptyList()
            val body = info.methods[key]
            if (body != null && !body.isStatic && !body.isPrivate && !body.isAbstract) {
                return listOf(MemberRef(current, key))
            }
            current = info.header.superName
        }
        return maximallySpecific(runtimeClass, key).filter { body(it)?.isAbstract == false }
    }

    /** Superinterface declarations of [key] that no other declaring superinterface overrides. */
    private fun maximallySpecific(owner: String, key: MemberKey): List<MemberRef> {
        val declaring = ancestors(owner).filter { ancestor ->
            val info = info(ancestor) ?: return@filter false
            info.header.isInterface && info.methods[key]?.let { !it.isStatic && !it.isPrivate } == true
        }
        if (declaring.size <= 1) return declaring.map { MemberRef(it, key) }
        return declaring
            .filter { candidate -> declaring.none { other -> other != candidate && candidate in ancestors(other) } }
            .map { MemberRef(it, key) }
    }

    private val platformKeyCache = HashMap<String, Set<MemberKey>>()

    /** Instance methods a JDK class declares that a classpath subclass could override. */
    fun platformOverridableKeys(platformClass: String): Set<MemberKey> =
        platformKeyCache.getOrPut(platformClass) {
            val info = platformInfo(platformClass) ?: return@getOrPut emptySet()
            info.methods.filter { (key, body) ->
                !body.isStatic && !body.isPrivate && key.name != "<init>" && key.name != "<clinit>"
            }.keys
        }
}
