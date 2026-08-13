package com.tampwell.staleguard.inspection

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlFile
import com.tampwell.staleguard.StaleguardBundle
import com.tampwell.staleguard.maven.PomDependencyCollector
import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.services.FreshnessRefreshService
import com.tampwell.staleguard.services.VersionLookupService
import com.tampwell.staleguard.version.MavenVersion
import com.tampwell.staleguard.version.UpgradeSeverity
import java.util.concurrent.TimeUnit
import org.jetbrains.idea.maven.dom.MavenDomUtil

/**
 * The core product surface: flags outdated and abandoned dependencies in
 * pom.xml.
 *
 * ARCHITECTURE INVARIANT (do not weaken): this runs inside highlighting
 * passes, so it must never suspend, fetch, or touch disk. It reads the warm
 * cache via [VersionLookupService.peek] only; unknown coordinates are handed
 * to [FreshnessRefreshService], which re-triggers highlighting when data
 * arrives.
 */
class DependencyFreshnessInspection : LocalInspectionTool() {

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (file !is XmlFile || file.name != "pom.xml") return null
        val project = file.project
        val virtualFile = file.virtualFile ?: return null
        val model = MavenDomUtil.getMavenDomProjectModel(project, virtualFile) ?: return null

        val lookup = VersionLookupService.getInstance()
        val refresh = FreshnessRefreshService.getInstance(project)
        val problems = mutableListOf<ProblemDescriptor>()
        val now = System.currentTimeMillis()

        for ((dom, declared) in PomDependencyCollector.collectWithDom(model)) {
            val groupId = declared.groupId ?: continue
            val artifactId = declared.artifactId ?: continue
            val coordinates = Coordinates(groupId, artifactId)

            val snapshot = lookup.peek(coordinates)
            if (snapshot == null) {
                // Never resolved this session — resolve in background, repaint later.
                refresh.requestLookup(coordinates)
                continue
            }
            val data = snapshot.value ?: continue // known absent (404): nothing to say

            // --- Freshness ---
            val current = declared.resolvedVersion?.let(::MavenVersion)
            val latestStable = data.latestStable
            if (current != null && latestStable != null) {
                val severity = UpgradeSeverity.classify(current, latestStable)
                if (severity != null) {
                    val anchor = dom.version.xmlTag ?: dom.xmlTag
                    if (anchor != null) {
                        val target = FixTarget.of(declared.rawVersion)
                        val fixes = when (target) {
                            FixTarget.None -> emptyArray()
                            else -> arrayOf(BumpVersionQuickFix(latestStable.value, target))
                        }
                        problems += manager.createProblemDescriptor(
                            anchor,
                            StaleguardBundle.message(
                                "inspection.outdated.message",
                                StaleguardBundle.message("severity.${severity.name.lowercase()}"),
                                current.value,
                                latestStable.value,
                            ),
                            isOnTheFly,
                            fixes,
                            highlightTypeFor(severity),
                        )
                    }
                }
            }

            // --- Abandonment: independent of freshness, per product decision ---
            val newestReleaseAt = data.newestReleaseAtMillis
            if (newestReleaseAt != null && now - newestReleaseAt > ABANDONMENT_THRESHOLD_MS) {
                val anchor = dom.artifactId.xmlTag ?: dom.xmlTag
                if (anchor != null) {
                    val years = TimeUnit.MILLISECONDS.toDays(now - newestReleaseAt) / 365
                    problems += manager.createProblemDescriptor(
                        anchor,
                        StaleguardBundle.message("inspection.abandoned.message", coordinates.toString(), years),
                        isOnTheFly,
                        emptyArray(),
                        ProblemHighlightType.WEAK_WARNING,
                    )
                }
            }
        }

        return problems.toTypedArray()
    }

    companion object {
        /** 2 years — product decision 2026-08-13; becomes a setting later. */
        val ABANDONMENT_THRESHOLD_MS: Long = TimeUnit.DAYS.toMillis(2 * 365)

        /**
         * MAJOR is deliberately the WEAKER highlight: a major bump is the
         * riskiest to apply, so it nudges rather than nags. MINOR/PATCH are
         * safe upgrades and get the standard warning. Pure function, tested.
         */
        fun highlightTypeFor(severity: UpgradeSeverity): ProblemHighlightType = when (severity) {
            UpgradeSeverity.MAJOR -> ProblemHighlightType.WEAK_WARNING
            UpgradeSeverity.MINOR -> ProblemHighlightType.GENERIC_ERROR_OR_WARNING
            UpgradeSeverity.PATCH -> ProblemHighlightType.GENERIC_ERROR_OR_WARNING
            UpgradeSeverity.QUALIFIER -> ProblemHighlightType.WEAK_WARNING
        }
    }
}
