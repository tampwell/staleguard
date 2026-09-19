package com.tampwell.staleguard.reach

import com.tampwell.staleguard.impact.MemberRef

/** The reachability answer for one advisory on one resolved artifact. */
sealed interface ReachVerdict {

    /** The project's own code can reach at least one method the fix changed. */
    data class Reached(
        /** False when only test code reaches it: the shipped code does not. */
        val inProduction: Boolean,
        /** The shortest explanation found, entry first, already rendered. */
        val path: List<String>,
        val touchedReached: Int,
        val touchedTotal: Int,
    ) : ReachVerdict

    /**
     * A complete walk of every classpath that carries the artifact reached
     * none of the methods the fix changed. A static statement: it demotes the
     * advisory, it never dismisses it.
     */
    data class NotReached(val touchedTotal: Int) : ReachVerdict

    data class Unknown(val reason: Reason) : ReachVerdict

    enum class Reason {
        /** The advisory names no fixed version to compare against. */
        NO_FIX_VERSION,

        /** The fixed release could not be downloaded, or offline mode is on. */
        FIX_NOT_FETCHED,

        /** The artifact's jar on disk could not be matched to these coordinates. */
        JAR_NOT_FOUND,

        /** The fix release changed no bytecode: the fix lives in resources or configuration. */
        FIX_CHANGED_NO_CODE,

        /** A walk stopped early or could not read part of the classpath. */
        ANALYSIS_INCOMPLETE,

        /** The project has no compiled classes to start from. */
        NOT_BUILT,
    }
}

object ReachVerdicts {

    /**
     * What one walk says about one set of fix-touched methods. Small on
     * purpose: a project with many modules walks many classpaths, and each
     * walk's full reached set can be dropped as soon as it has been observed.
     */
    class Observation internal constructor(
        val tests: Boolean,
        val complete: Boolean,
        val reached: Set<MemberRef>,
        val shortest: List<Reachability.Step>?,
    )

    fun observe(touched: Set<MemberRef>, result: Reachability.Result, tests: Boolean): Observation {
        var shortest: List<Reachability.Step>? = null
        val reached = HashSet<MemberRef>()
        for (method in touched) {
            val path = result.path(method) ?: continue
            reached += method
            if (shortest == null || path.size < shortest.size) shortest = path
        }
        return Observation(tests, result.complete, reached, shortest)
    }

    /**
     * [observations] come from every walk whose classpath carries the
     * vulnerable jar. Production wins over tests, a reached method wins over
     * any incompleteness (a path found is found), and "not reached" requires
     * every relevant walk to be complete and at least one to exist.
     */
    fun decide(touched: Set<MemberRef>, observations: List<Observation>): ReachVerdict {
        if (touched.isEmpty()) return ReachVerdict.Unknown(ReachVerdict.Reason.FIX_CHANGED_NO_CODE)
        if (observations.isEmpty()) return ReachVerdict.Unknown(ReachVerdict.Reason.JAR_NOT_FOUND)

        for (tests in listOf(false, true)) {
            val relevant = observations.filter { it.tests == tests && it.shortest != null }
            val shortest = relevant.mapNotNull { it.shortest }.minByOrNull { it.size } ?: continue
            val reached = relevant.flatMapTo(HashSet()) { it.reached }
            return ReachVerdict.Reached(!tests, ReachPaths.render(shortest), reached.size, touched.size)
        }
        if (observations.any { !it.complete }) return ReachVerdict.Unknown(ReachVerdict.Reason.ANALYSIS_INCOMPLETE)
        return ReachVerdict.NotReached(touched.size)
    }

    /** Convenience over whole walks, for callers holding them anyway. */
    fun decide(
        touched: Set<MemberRef>,
        production: List<Reachability.Result>,
        tests: List<Reachability.Result>,
    ): ReachVerdict = decide(
        touched,
        production.map { observe(touched, it, tests = false) } + tests.map { observe(touched, it, tests = true) },
    )
}

/** Explanations a person can read: short names, and the edges that are not plain calls said out loud. */
object ReachPaths {

    /** Longer paths keep their start and end; the middle is where readers lose the thread anyway. */
    const val MAX_STEPS = 8

    fun render(path: List<Reachability.Step>): List<String> {
        val steps = path.map { step ->
            val owner = step.method.owner.substringAfterLast('/').replace('$', '.')
            val name = when (step.method.name) {
                "<init>" -> "new"
                "<clinit>" -> "static init"
                else -> step.method.name
            }
            val label = "$owner.$name"
            when (step.via) {
                Reachability.Edge.STATIC_INIT, Reachability.Edge.CALL, Reachability.Edge.ENTRY -> label
                Reachability.Edge.RUNTIME_CALLBACK -> "$label (called back by the JDK)"
                Reachability.Edge.FRAMEWORK -> "$label (run by the framework)"
            }
        }
        if (steps.size <= MAX_STEPS) return steps
        return steps.take(3) + "..." + steps.takeLast(MAX_STEPS - 4)
    }
}
