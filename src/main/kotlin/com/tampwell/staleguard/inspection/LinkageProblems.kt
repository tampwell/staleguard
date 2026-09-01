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

    /**
     * Version-exact on purpose: the warning describes the version the audit
     * saw breaking, so a declaration the user already bumped must stop
     * matching immediately rather than nagging until the next audit.
     */
    fun problemFor(project: Project, coordinates: Coordinates, resolvedVersion: String?): LinkageVerdictState.JarProblem? =
        LinkageVerdictState.getInstance(project).problems[coordinates]
            ?.takeIf { resolvedVersion != null && it.version == resolvedVersion }

    fun message(problem: LinkageVerdictState.JarProblem): String {
        val callers = problem.callers.joinToString(", ")
        val base = if (problem.fixVersion != null) {
            StaleguardBundle.message("inspection.linkage.message.fix", problem.brokenCalls, callers, problem.fixVersion)
        } else {
            StaleguardBundle.message("inspection.linkage.message", problem.brokenCalls, callers)
        }
        // One path is a tooltip's worth of provenance; the dialog has them all.
        val via = problem.provenance.firstOrNull()
            ?.let { StaleguardBundle.message("inspection.linkage.via", it) }
            .orEmpty()
        return base + via
    }
}
