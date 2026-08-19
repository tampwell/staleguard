package com.tampwell.staleguard.inspection

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.project.Project
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.policy.ProjectPolicyService

/**
 * License-policy check shared by all four inspection surfaces. Nothing fires
 * unless the project committed a [licenses] table in .staleguard.toml —
 * license opinions belong to teams, not to the plugin.
 *
 * The licenses examined are the ones published in the NEWEST version's POM
 * (that is what the version cache carries). Licenses changing between
 * versions is rare enough that this is the honest tradeoff over fetching
 * every declared version's POM.
 */
object LicenseProblems {

    data class Finding(val message: String, val highlight: ProblemHighlightType)

    fun check(project: Project, coordinates: String, licenses: List<String>): Finding? {
        if (licenses.isEmpty()) return null
        val policy = ProjectPolicyService.getInstance(project).licensePolicy()
        if (policy.isEmpty) return null
        policy.deniedLicense(licenses)?.let {
            return Finding(
                StaleguardBundle.message("inspection.license.denied", it, coordinates),
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
            )
        }
        policy.warnedLicense(licenses)?.let {
            return Finding(
                StaleguardBundle.message("inspection.license.warned", it, coordinates),
                ProblemHighlightType.WEAK_WARNING,
            )
        }
        return null
    }
}
