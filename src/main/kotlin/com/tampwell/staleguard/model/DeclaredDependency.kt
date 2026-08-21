package com.tampwell.staleguard.model

/**
 * A dependency exactly as declared in a build file, before any resolution
 * against remote repositories.
 *
 * [rawVersion] is the literal text in the build file (may contain `${property}`
 * placeholders, or be null when the version is managed elsewhere, e.g. by a BOM
 * or a parent pom). [resolvedVersion] is the raw version after property
 * interpolation — still null if nothing was declared.
 */
data class DeclaredDependency(
    val groupId: String?,
    val artifactId: String?,
    val rawVersion: String?,
    val resolvedVersion: String?,
    val origin: Origin,
) {
    enum class Origin { DEPENDENCIES, DEPENDENCY_MANAGEMENT, PARENT, BOM_IMPORT, BUILD_PLUGIN }

    val coordinate: String
        get() = "${groupId ?: "?"}:${artifactId ?: "?"}"

    override fun toString(): String {
        val version = when {
            resolvedVersion == null -> "(managed)"
            resolvedVersion != rawVersion -> "$resolvedVersion (declared as $rawVersion)"
            else -> resolvedVersion
        }
        return "$coordinate:$version [${origin.name.lowercase()}]"
    }
}
