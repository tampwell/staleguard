package com.tampwell.staleguard.reach

import com.tampwell.staleguard.impact.MemberKey
import com.tampwell.staleguard.impact.MemberRef

/**
 * Static reachability over a whole runtime classpath: starting from the
 * project's own code, which methods can ever run.
 *
 * One multi-source breadth-first walk answers every target at once and
 * leaves shortest explanations behind, which is why no per-library summary
 * precomputation is needed here: the published compositional designs
 * (ReachCheck, TOSEM 2025) amortize millions of pairwise queries, while this
 * question is a single walk bounded by what the program can actually reach.
 *
 * Dispatch model, chosen so that a "not reached" answer is never wrong for a
 * reason visible in the bytecode:
 *  - A virtual call whose static receiver type is a classpath type reaches
 *    the implementation in every subtype whose instances can exist (class
 *    hierarchy analysis). Every named class counts: plugin systems create
 *    them by name, often through static factories that are themselves
 *    invoked reflectively (this is how Log4j builds the converter on the
 *    Log4Shell path), so no static evidence can rule one out. Local and
 *    anonymous classes, which include every compiler-generated lambda and
 *    coroutine class, count once the walk reaches their enclosing code, the
 *    only code that ever creates them. Without that one refinement a real
 *    Kotlin classpath measured two thirds reached, an answer that means
 *    nothing.
 *  - A call through a JDK type (Object, Runnable, List...) cannot be
 *    followed, because JDK bodies are not analyzed. Instead, every class the
 *    program instantiates has its overrides of JDK methods treated as
 *    reachable: once an object exists, the JDK may call back into it. This is
 *    what keeps Object.toString from reaching every toString ever written.
 *  - Static initializers run when their class is first used; ServiceLoader
 *    providers and Spring auto-configuration run with no static call at all.
 *
 * Outside the model, and stated wherever a verdict is shown: reflection
 * (Method.invoke, method handles looked up by string), dynamic proxies, and
 * classes created only reflectively that are reached only through JDK
 * callbacks.
 */
class Reachability private constructor(
    private val hierarchy: ClassHierarchy,
    private val maxMethods: Int,
    private val checkCanceled: () -> Unit,
) {

    /** Why a method is on the reached set. */
    enum class Edge {
        /** The project's own code: every method of it is a starting point. */
        ENTRY,

        /** A call instruction, a lambda body, or a method reference. */
        CALL,

        /** A static initializer, run when its class is first used. */
        STATIC_INIT,

        /** An override of a JDK method on an object the parent creates; the JDK may call it. */
        RUNTIME_CALLBACK,

        /** Run by the runtime or a framework with no static call: ServiceLoader, Spring. */
        FRAMEWORK,
    }

    data class Step(val method: MemberRef, val via: Edge)

    class Result internal constructor(
        private val parents: Map<MemberRef, Node>,
        /** False when the walk stopped early or could not read part of the classpath. */
        val complete: Boolean,
        /** Human-readable reasons [complete] is false. */
        val gaps: List<String>,
        val reachedMethods: Int,
        val classesRead: Int,
        val millis: Long,
        /** Virtual call sites by how many implementations they dispatch to; for tuning, not display. */
        internal val topFanout: List<Pair<MemberRef, Int>> = emptyList(),
    ) {
        fun reaches(method: MemberRef): Boolean = method in parents

        /** Entry first, [method] last; null when it is not reached. */
        fun path(method: MemberRef): List<Step>? {
            if (method !in parents) return null
            val steps = ArrayList<Step>()
            var current: MemberRef? = method
            val guard = HashSet<MemberRef>()
            while (current != null && guard.add(current)) {
                val node = parents.getValue(current)
                steps += Step(current, node.via)
                current = node.parent
            }
            return steps.asReversed()
        }
    }

    internal class Node(val parent: MemberRef?, val via: Edge)

    private val nodes = HashMap<MemberRef, Node>()
    private val queue = ArrayDeque<MemberRef>()
    private val initialized = HashSet<String>()
    private val instantiated = HashSet<String>()
    private val gaps = ArrayList<String>()
    private var budgetHit = false

    /** An expanded virtual call site: who first made it, and whether it dispatches at all. */
    private class Site(val caller: MemberRef, val dispatches: Boolean)

    /** Expanded virtual call sites, by static receiver type then method. */
    private val sites = HashMap<String, HashMap<MemberKey, Site>>()
    private val fanout = HashMap<MemberRef, Int>()

    /**
     * A class whose instances can exist. Named classes always can: plugin
     * systems create them by name, often through a static factory that is
     * itself invoked reflectively, so no static evidence can rule them out.
     * Local and anonymous classes (compiler-generated lambdas and coroutine
     * state machines among them) are created only by their enclosing code,
     * so they exist once the walk reaches that code; one no code creates at
     * all is kept too, since then only the runtime can have made it.
     */
    private fun possiblyCreated(className: String): Boolean =
        !hierarchy.isLocalOrAnonymous(className) ||
            className in instantiated ||
            !hierarchy.isCreatedInCode(className)

    private fun reach(method: MemberRef, parent: MemberRef?, via: Edge) {
        // JDK bodies are opaque; their callbacks are modelled by instantiation.
        if (!hierarchy.isClasspath(method.owner)) return
        if (method in nodes) return
        if (nodes.size >= maxMethods) {
            budgetHit = true
            return
        }
        nodes[method] = Node(parent, via)
        queue.addLast(method)
    }

    /** JVMS 5.5: a class's initialization first initializes its superclass and default-bearing superinterfaces. */
    private fun initialize(className: String, parent: MemberRef?) {
        if (!hierarchy.isClasspath(className) || !initialized.add(className)) return
        val info = hierarchy.info(className) ?: return
        if (CLINIT in info.methods) reach(MemberRef(className, CLINIT), parent, Edge.STATIC_INIT)
        info.header.superName?.let { initialize(it, parent) }
        for (parentInterface in info.header.interfaces) {
            val interfaceInfo = hierarchy.info(parentInterface) ?: continue
            if (interfaceInfo.methods.values.any { !it.isAbstract && !it.isStatic }) initialize(parentInterface, parent)
        }
    }

    private fun instantiate(className: String, parent: MemberRef?) {
        if (!hierarchy.isClasspath(className) || !instantiated.add(className)) return
        initialize(className, parent)
        val types = listOf(className) + hierarchy.ancestors(className)

        // Once an object exists the JDK may call back into it, but only
        // through methods a JDK type declares; everything else a caller must
        // name, and naming it is a call the walk sees.
        val jdkKeys = HashSet<MemberKey>()
        for (type in types) {
            if (hierarchy.isPlatform(type)) jdkKeys += hierarchy.platformOverridableKeys(type)
        }
        if (jdkKeys.isNotEmpty()) {
            for (type in types) {
                if (!hierarchy.isClasspath(type)) continue
                val info = hierarchy.info(type) ?: continue
                for ((key, body) in info.methods) {
                    if (key !in jdkKeys || body.isStatic || body.isPrivate) continue
                    for (target in hierarchy.select(className, key)) reach(target, parent, Edge.RUNTIME_CALLBACK)
                }
            }
        }

        // Virtual call sites expanded before this class could exist dispatch
        // to it now, so the answer never depends on the order of the walk.
        // Only local classes wait for their creation; the rest were receivers
        // from the start.
        if (!hierarchy.isLocalOrAnonymous(className) || !hierarchy.isCreatedInCode(className)) return
        if (!hierarchy.isConcrete(className)) return
        for (type in types) {
            val expanded = sites[type] ?: continue
            for ((key, site) in expanded) {
                if (!site.dispatches) continue
                for (target in hierarchy.select(className, key)) {
                    reach(target, site.caller, Edge.CALL)
                    fanout.merge(MemberRef(type, key), 1, Int::plus)
                }
            }
        }
    }

    private fun virtualCall(owner: String, key: MemberKey, caller: MemberRef) {
        // Through a JDK type: modelled by instantiation callbacks instead.
        if (!hierarchy.isClasspath(owner)) return
        val expanded = sites.getOrPut(owner) { HashMap() }
        // Expanded before: creations since then were pushed by instantiate().
        if (key in expanded) return

        val resolved = hierarchy.resolve(owner, key)
        val single = resolved.singleOrNull()
        val singleBody = single?.let(hierarchy::body)
        // Private (nestmate) and static targets never dispatch.
        if (single != null && singleBody != null && (singleBody.isPrivate || singleBody.isStatic)) {
            expanded[key] = Site(caller, dispatches = false)
            reach(single, caller, Edge.CALL)
            return
        }
        expanded[key] = Site(caller, dispatches = true)

        val targets = LinkedHashSet<MemberRef>()
        // The resolved implementation itself, even when no loaded class
        // inherits it concretely: generated subclasses and proxies can.
        for (method in resolved) {
            if (hierarchy.body(method)?.isAbstract == false) targets += method
        }
        for (candidate in sequenceOf(owner) + hierarchy.subtypes(owner).asSequence()) {
            if (hierarchy.isConcrete(candidate) && possiblyCreated(candidate)) targets += hierarchy.select(candidate, key)
        }
        fanout[MemberRef(owner, key)] = targets.size
        for (target in targets) reach(target, caller, Edge.CALL)
    }

    private fun run(
        entryClasses: Collection<String>,
        serviceProviders: Collection<String>,
        frameworkEntries: Collection<String>,
        atPeak: () -> Unit,
    ): Result {
        val started = System.nanoTime()
        for (className in entryClasses) {
            val info = hierarchy.info(className) ?: continue
            for (key in info.methods.keys) reach(MemberRef(className, key), null, Edge.ENTRY)
            instantiate(className, null)
        }
        for (className in frameworkEntries) {
            val info = hierarchy.info(className) ?: continue
            for (key in info.methods.keys) reach(MemberRef(className, key), null, Edge.FRAMEWORK)
            instantiate(className, null)
        }
        for (className in serviceProviders) {
            val info = hierarchy.info(className) ?: continue
            // ServiceLoader requires a public no-argument constructor.
            if (NO_ARG_INIT in info.methods) reach(MemberRef(className, NO_ARG_INIT), null, Edge.FRAMEWORK)
            instantiate(className, null)
        }

        var processed = 0
        while (queue.isNotEmpty()) {
            if (++processed and 0xFFF == 0) checkCanceled()
            val method = queue.removeFirst()
            val body = hierarchy.body(method) ?: continue
            for (call in body.calls) {
                when (call.dispatch) {
                    Dispatch.STATIC, Dispatch.SPECIAL ->
                        for (target in hierarchy.resolve(call.owner, call.key)) reach(target, method, Edge.CALL)
                    Dispatch.VIRTUAL -> virtualCall(call.owner, call.key, method)
                }
            }
            for (className in body.initializes) initialize(className, method)
            for (className in body.instantiates) instantiate(className, method)
        }

        // Everything the walk built is still referenced here: its high-water mark.
        atPeak()

        if (budgetHit) gaps += "stopped after $maxMethods reachable methods"
        if (hierarchy.unreadable.isNotEmpty()) {
            gaps += "${hierarchy.unreadable.size} unreadable classes, e.g. ${hierarchy.unreadable.first()}"
        }
        return Result(
            parents = nodes,
            complete = gaps.isEmpty(),
            gaps = gaps.toList(),
            reachedMethods = nodes.size,
            classesRead = hierarchy.classpathClasses.size,
            millis = (System.nanoTime() - started) / 1_000_000,
            topFanout = fanout.entries.sortedByDescending { it.value }.take(25).map { it.key to it.value },
        )
    }

    companion object {
        private val CLINIT = MemberKey("<clinit>", "()V")
        private val NO_ARG_INIT = MemberKey("<init>", "()V")

        /** Enough for very large applications; beyond it the answer is stated as incomplete, never guessed. */
        const val DEFAULT_MAX_METHODS = 2_000_000

        /**
         * [entryClasses] are the project's own classes: every method in them
         * is a starting point, since frameworks, tests and main methods call
         * into them in ways no static analysis can enumerate.
         */
        fun analyze(
            source: ClassSource,
            entryClasses: Collection<String>,
            platformBytes: (String) -> ByteArray? = PlatformClasses::bytes,
            maxMethods: Int = DEFAULT_MAX_METHODS,
            checkCanceled: () -> Unit = {},
        ): Result = analyze(source, entryClasses, platformBytes, maxMethods, checkCanceled, atPeak = {})

        /** [atPeak] runs while every structure the walk built is still live; tests measure memory there. */
        internal fun analyze(
            source: ClassSource,
            entryClasses: Collection<String>,
            platformBytes: (String) -> ByteArray?,
            maxMethods: Int,
            checkCanceled: () -> Unit,
            atPeak: () -> Unit,
        ): Result = Reachability(ClassHierarchy(source, platformBytes), maxMethods, checkCanceled)
            .run(entryClasses, source.serviceProviders, source.frameworkEntries, atPeak)
    }
}
