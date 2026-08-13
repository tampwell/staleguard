package com.tampwell.staleguard.maven

import com.intellij.openapi.project.Project
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * Cross-module blast-radius scan for a version property: which declared
 * dependencies (in any Maven module of the project) resolve their version
 * through `${propertyName}`? Must be called under a read action.
 */
object PropertyUsageFinder {

    data class Usage(val moduleName: String, val coordinates: String, val resolvedVersion: String?)

    fun usages(project: Project, propertyName: String): List<Usage> {
        val reference = "\${$propertyName}"
        val result = mutableListOf<Usage>()
        for (mavenProject in MavenProjectsManager.getInstance(project).projects) {
            val model = MavenDomUtil.getMavenDomProjectModel(project, mavenProject.file) ?: continue
            for ((_, declared) in PomDependencyCollector.collectWithDom(model)) {
                if (declared.rawVersion?.contains(reference) == true) {
                    result.add(Usage(mavenProject.displayName, declared.coordinate, declared.resolvedVersion))
                }
            }
        }
        return result
    }
}
