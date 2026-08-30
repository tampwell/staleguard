package com.tampwell.staleguard.inspection

import com.intellij.openapi.project.Project
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.impact.LinkageVerdictState
import com.tampwell.staleguard.repository.Coordinates

/**
 * The linkage warning shared by all four inspection surfaces: a declaration
 * whose RESOLVED version breaks other jars' calls gets told so exactly where
 * the version is chosen. Reads only the ambient verdict the audits maintain —
 * highlighting never scans a classpath.
 */
object LinkageProblems {

    fun problemFor(project: Project, coordinates: Coordinates): LinkageVerdictState.JarProblem? =
        LinkageVerdictState.getInstance(project).problems[coordinates]

    fun message(problem: LinkageVerdictState.JarProblem): String {
        val callers = problem.callers.joinToString(", ")
        return if (problem.fixVersion != null) {
            StaleguardBundle.message("inspection.linkage.message.fix", problem.brokenCalls, callers, problem.fixVersion)
        } else {
            StaleguardBundle.message("inspection.linkage.message", problem.brokenCalls, callers)
        }
    }
}
