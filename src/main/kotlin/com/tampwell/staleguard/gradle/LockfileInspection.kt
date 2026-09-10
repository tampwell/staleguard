package com.tampwell.staleguard.gradle

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.inspection.IgnoreDependencyQuickFix
import com.tampwell.staleguard.inspection.OpenAdvisoryQuickFix
import com.tampwell.staleguard.inspection.VulnerabilityProblems
import com.tampwell.staleguard.repository.Coordinates

/**
 * Warnings inside a Gradle lockfile: a line whose locked version is out of
 * step with what the build file in the same directory declares (the
 * forgot-to-relock footgun), and a line whose locked version has a known
 * vulnerability - the locked version being exactly what the build runs.
 *
 * Deliberately NO quick fix edits the lockfile: its own generated header
 * says manual edits break the build. The drift message names the honest
 * fix instead - re-run Gradle with --write-locks.
 *
 * Registered for plain text (the platform's TEXT language), and bails on
 * the first line unless the file is actually a Gradle lock.
 */
class LockfileInspection : LocalInspectionTool() {

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val virtualFile = file.virtualFile ?: return null
        val located = Lockfile.locate(virtualFile.path.replace('\\', '/')) ?: return null

        val declared = if (located.driftEligible) declaredBeside(virtualFile, located) else emptyList()
        val declaredByCoordinate = declared
            .filterNot { Lockfile.isDynamic(it.version) }
            .associateBy { it.group to it.name }

        val policy = com.tampwell.staleguard.policy.ProjectPolicyService.getInstance(manager.project)
        val problems = mutableListOf<ProblemDescriptor>()
        for ((locked, range) in Lockfile.parseLines(file.text, located.fallbackConfiguration)) {
            val lineRange = TextRange(range.first, range.last + 1)
            if (policy.isIgnored(locked.group, locked.name)) continue

            val declaration = declaredByCoordinate[locked.group to locked.name]
            if (declaration != null && declaration.version != locked.version) {
                problems += manager.createProblemDescriptor(
                    file,
                    lineRange,
                    StaleguardBundle.message("inspection.lockfile.drift", locked.version, declaration.version),
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    isOnTheFly,
                )
            }

            val advisories = VulnerabilityProblems.advisoriesFor(
                manager.project,
                Coordinates(locked.group, locked.name),
                locked.version,
            )
            if (!advisories.isNullOrEmpty()) {
                val worst = VulnerabilityProblems.worst(advisories)
                problems += manager.createProblemDescriptor(
                    file,
                    lineRange,
                    VulnerabilityProblems.message(advisories) +
                        StaleguardBundle.message("inspection.lockfile.vuln.note"),
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    isOnTheFly,
                    OpenAdvisoryQuickFix(worst.url, worst.displayId),
                    IgnoreDependencyQuickFix(locked.group, locked.name),
                )
            }
        }
        return problems.toTypedArray()
    }

    /**
     * What the build file next to this lockfile declares, resolved through
     * the same catalog and gradle.properties machinery the batch applier
     * trusts. Empty when there is no sibling build file to compare against.
     */
    private fun declaredBeside(virtualFile: VirtualFile, located: Lockfile.Located): List<Lockfile.Declared> {
        var moduleDir: VirtualFile? = virtualFile.parent
        if (located.fallbackConfiguration != null) {
            // Legacy layout: <module>/gradle/dependency-locks/<conf>.lockfile
            moduleDir = moduleDir?.parent?.parent
        }
        val buildFile = moduleDir?.findChild("build.gradle.kts") ?: moduleDir?.findChild("build.gradle") ?: return emptyList()
        val document = FileDocumentManager.getInstance().getDocument(buildFile) ?: return emptyList()

        val catalogFile = KtsDependencyCollector.findCatalogFile(buildFile)
        val catalog = catalogFile
            ?.let { FileDocumentManager.getInstance().getDocument(it) }
            ?.let { VersionCatalog.parse(it.text) }
            ?: VersionCatalog.EMPTY
        val propertiesFile = GradleProperties.findFile(buildFile)
        val gradleProperties = propertiesFile
            ?.let { FileDocumentManager.getInstance().getDocument(it) }
            ?.let { GradleProperties.parse(it.text) }
            .orEmpty()

        return GradleTextScanner.scan(document.text, catalog, gradleProperties, includePluginBlocks = false)
            .map { Lockfile.Declared(it.group, it.name, it.version) }
    }
}
