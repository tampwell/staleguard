package com.tampwell.staleguard.impact

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.actions.UpgradeApplier
import com.tampwell.staleguard.repository.Coordinates
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * Executes a [LinkageFixPlan]: bumps ride the shared batch applier (one write
 * command, one undo step), dependencyManagement pins go through the Maven DOM
 * so formatting is the platform's problem, and snippets are only reported —
 * pasting into a Gradle script stays the user's hand.
 */
object LinkageFixApplier {

    data class Applied(val lines: List<String>, val snippets: List<String>)

    fun apply(project: Project, steps: List<LinkageFixPlan.Step>): Applied {
        val lines = mutableListOf<String>()
        val snippets = mutableListOf<String>()

        val bumps = steps.filterIsInstance<LinkageFixPlan.Step.Bump>()
        if (bumps.isNotEmpty()) {
            val applied = UpgradeApplier.applyCandidates(project, bumps.map { it.candidate })
            for (bump in bumps) {
                lines += StaleguardBundle.message(
                    "linkage.apply.bumped",
                    "${bump.candidate.coordinates.groupId}:${bump.candidate.coordinates.artifactId}",
                    bump.candidate.suggestedVersion.value,
                )
            }
            if (applied < bumps.size) {
                lines += StaleguardBundle.message("linkage.apply.partial", bumps.size - applied)
            }
        }

        for (step in steps.filterIsInstance<LinkageFixPlan.Step.Manage>()) {
            val added = addManagedPin(project, step.coordinates, step.version)
            lines += if (added) {
                StaleguardBundle.message(
                    "linkage.apply.managed",
                    "${step.coordinates.groupId}:${step.coordinates.artifactId}",
                    step.version,
                )
            } else {
                StaleguardBundle.message(
                    "linkage.apply.failed",
                    "${step.coordinates.groupId}:${step.coordinates.artifactId}",
                    "could not edit the root pom",
                )
            }
        }

        for (step in steps.filterIsInstance<LinkageFixPlan.Step.Snippet>()) {
            lines += StaleguardBundle.message(
                "linkage.apply.snippet",
                "${step.coordinates.groupId}:${step.coordinates.artifactId}",
                step.version,
            )
            snippets += step.text
        }
        for (step in steps.filterIsInstance<LinkageFixPlan.Step.Unappliable>()) {
            lines += StaleguardBundle.message("linkage.apply.failed", step.jarName, step.reason)
        }
        return Applied(lines, snippets)
    }

    /**
     * The pin goes into the ROOT pom's dependencyManagement: that is the one
     * place that governs every module, which matches what the fix means.
     */
    private fun addManagedPin(project: Project, coordinates: Coordinates, version: String): Boolean {
        val root = MavenProjectsManager.getInstance(project).rootProjects.firstOrNull() ?: return false
        var added = false
        WriteCommandAction.runWriteCommandAction(project, StaleguardBundle.message("linkage.apply.command"), null, {
            val model = MavenDomUtil.getMavenDomProjectModel(project, root.file)
                ?: return@runWriteCommandAction
            val dependency = model.dependencyManagement.dependencies.addDependency()
            dependency.groupId.stringValue = coordinates.groupId
            dependency.artifactId.stringValue = coordinates.artifactId
            dependency.version.stringValue = version
            added = true
        })
        return added
    }
}
