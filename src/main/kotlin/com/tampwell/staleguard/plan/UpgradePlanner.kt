package com.tampwell.staleguard.plan

import com.tampwell.staleguard.inspection.FixTarget
import com.tampwell.staleguard.model.DeclaredDependency
import com.tampwell.staleguard.repository.ArtifactVersions
import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.version.MavenVersion
import com.tampwell.staleguard.version.UpgradeSeverity
import com.tampwell.staleguard.version.VersionSuggestion

/** One declared dependency plus what we know about it — the planner's input row. */
data class PlannerInput(
    val moduleName: String,
    val declared: DeclaredDependency,
    /** Warm-cache data, or null when not resolved / not found. */
    val known: ArtifactVersions?,
    /** Stable identifier for the module (e.g. pom file path); display name is not unique. */
    val moduleId: String = moduleName,
)

/** An applicable upgrade: where it is, what it becomes, how risky it looks. */
data class UpgradeCandidate(
    val moduleName: String,
    val coordinates: Coordinates,
    val currentVersion: MavenVersion,
    val suggestedVersion: MavenVersion,
    val severity: UpgradeSeverity,
    /** Literal or Property — None never becomes a candidate. */
    val target: FixTarget,
    val recommendation: Recommendation,
    val moduleId: String = moduleName,
    val confidence: UpdateConfidence = UpdateConfidence(50, emptyList()),
) {
    val propertyName: String? get() = (target as? FixTarget.Property)?.name
}

/** The full picture for a project: candidates plus property blast-radius. */
data class UpgradePlan(
    val candidates: List<UpgradeCandidate>,
    /**
     * property name -> how many DECLARED dependencies reference it (not just
     * upgradable ones) — the impact number shown before editing a property.
     */
    val propertyUsage: Map<String, Int>,
) {
    fun impactOf(propertyName: String): Int = propertyUsage[propertyName] ?: 0
}

/**
 * Pure planning logic shared by the batch dialog, the property-safety check,
 * and (via [Recommendation]) the inspection messages. No platform imports —
 * exhaustively unit-tested.
 */
object UpgradePlanner {

    private val PROPERTY_REF = Regex("""\$\{([^{}]+)}""")

    fun plan(
        inputs: List<PlannerInput>,
        suggestPrereleases: Boolean,
        abandonmentThresholdMillis: Long,
        ignored: (groupId: String, artifactId: String) -> Boolean,
        nowMillis: Long,
        /** Warm-cache advisory count for the CURRENT version — escalates the recommendation to URGENT. */
        advisoryCount: (groupId: String, artifactId: String, version: String) -> Int = { _, _, _ -> 0 },
    ): UpgradePlan {
        val propertyUsage = mutableMapOf<String, Int>()
        for (input in inputs) {
            val raw = input.declared.rawVersion ?: continue
            for (match in PROPERTY_REF.findAll(raw)) {
                val name = match.groupValues[1]
                propertyUsage[name] = (propertyUsage[name] ?: 0) + 1
            }
        }

        val candidates = inputs.mapNotNull { input ->
            val declared = input.declared
            val groupId = declared.groupId ?: return@mapNotNull null
            val artifactId = declared.artifactId ?: return@mapNotNull null
            if (ignored(groupId, artifactId)) return@mapNotNull null
            val known = input.known ?: return@mapNotNull null
            val current = declared.resolvedVersion?.let(::MavenVersion) ?: return@mapNotNull null
            val suggested = VersionSuggestion.suggest(current, known.versions, suggestPrereleases)
                ?: return@mapNotNull null
            val severity = UpgradeSeverity.classify(current, suggested) ?: return@mapNotNull null
            val target = FixTarget.of(declared.rawVersion)
            if (target == FixTarget.None) return@mapNotNull null

            val releaseAge = known.newestReleaseAtMillis?.let { nowMillis - it }
            val abandoned = releaseAge != null && releaseAge > abandonmentThresholdMillis
            val vulnerable = advisoryCount(groupId, artifactId, current.value) > 0
            UpgradeCandidate(
                moduleName = input.moduleName,
                coordinates = Coordinates(groupId, artifactId),
                currentVersion = current,
                suggestedVersion = suggested,
                severity = severity,
                target = target,
                recommendation = Recommendation.of(severity, releaseAge, abandoned, vulnerable),
                moduleId = input.moduleId,
                confidence = ConfidenceScorer.score(severity, releaseAge, abandoned, vulnerable),
            )
        }

        return UpgradePlan(candidates, propertyUsage)
    }
}
