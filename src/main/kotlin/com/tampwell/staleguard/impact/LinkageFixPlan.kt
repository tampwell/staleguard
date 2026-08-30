package com.tampwell.staleguard.impact

import com.tampwell.staleguard.inspection.FixTarget
import com.tampwell.staleguard.plan.PlannerInput
import com.tampwell.staleguard.plan.Recommendation
import com.tampwell.staleguard.plan.UpgradeCandidate
import com.tampwell.staleguard.repository.Coordinates
import com.tampwell.staleguard.version.MavenVersion
import com.tampwell.staleguard.version.UpgradeSeverity

/**
 * Decides HOW each computed fix gets into the build, purely, so the decision
 * table is testable without a project.
 *
 * A fix names a jar and the earliest version that satisfies every call. If
 * the artifact is declared directly, the existing bump machinery edits the
 * declaration (literal, property, catalog — whatever governs it). If it
 * arrived transitively, no declaration exists to edit: Maven builds get a
 * dependencyManagement entry, which is the tool-blessed way to pin a
 * transitive version, and Gradle builds get the exact constraints block to
 * paste, because inserting text into someone's build script is not an edit,
 * it is a guess about their formatting and their conventions.
 */
object LinkageFixPlan {

    sealed interface Step {
        /** A direct declaration exists; bump it through the shared applier. */
        data class Bump(val candidate: UpgradeCandidate) : Step

        /** Transitive in a Maven build: add a dependencyManagement entry. */
        data class Manage(val coordinates: Coordinates, val version: String) : Step

        /** Transitive in a Gradle build: hand over the constraint to paste. */
        data class Snippet(val coordinates: Coordinates, val version: String, val text: String) : Step

        /** Nothing safe to do; the reason is shown, never silence. */
        data class Unappliable(val jarName: String, val reason: String) : Step
    }

    fun plan(
        fixes: Map<String, FixSuggestions.Suggestion>,
        identify: (jarName: String) -> JarCoordinates.Identified?,
        inputs: List<PlannerInput>,
        mavenBuild: Boolean,
    ): List<Step> = fixes.mapNotNull { (jarName, suggestion) ->
        val version = (suggestion as? FixSuggestions.Suggestion.FixedIn)?.version ?: return@mapNotNull null
        val identified = identify(jarName)
            ?: return@mapNotNull Step.Unappliable(jarName, "not identifiable as Maven coordinates")

        val declared = inputs.firstOrNull { input ->
            input.declared.groupId == identified.coordinates.groupId &&
                input.declared.artifactId == identified.coordinates.artifactId
        }
        when {
            declared != null -> {
                val target = FixTarget.of(declared.declared.rawVersion)
                val current = declared.declared.resolvedVersion ?: declared.declared.rawVersion
                if (target == FixTarget.None || current == null) {
                    // Declared but managed elsewhere (parent, BOM, mixed text):
                    // the safe move is the same as for a transitive.
                    pinStep(identified.coordinates, version, mavenBuild)
                } else {
                    Step.Bump(
                        UpgradeCandidate(
                            moduleName = declared.moduleName,
                            coordinates = identified.coordinates,
                            currentVersion = MavenVersion(current),
                            suggestedVersion = MavenVersion(version),
                            severity = UpgradeSeverity.classify(MavenVersion(current), MavenVersion(version))
                                ?: return@mapNotNull Step.Unappliable(
                                    jarName,
                                    "the declared version $current is already at or past $version",
                                ),
                            target = target,
                            recommendation = Recommendation.URGENT,
                            moduleId = declared.moduleId,
                        ),
                    )
                }
            }
            else -> pinStep(identified.coordinates, version, mavenBuild)
        }
    }

    private fun pinStep(coordinates: Coordinates, version: String, mavenBuild: Boolean): Step =
        if (mavenBuild) {
            Step.Manage(coordinates, version)
        } else {
            Step.Snippet(coordinates, version, constraintSnippet(coordinates, version))
        }

    fun constraintSnippet(coordinates: Coordinates, version: String): String = """
        dependencies {
            constraints {
                implementation("${coordinates.groupId}:${coordinates.artifactId}:$version")
            }
        }
    """.trimIndent()
}
