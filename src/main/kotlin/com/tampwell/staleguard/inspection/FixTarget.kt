package com.tampwell.staleguard.inspection

/**
 * Where a version bump must be written, decided from the dependency's RAW
 * declared version. Pure logic, unit-tested — getting this wrong writes to
 * the wrong place in someone's pom.
 */
sealed interface FixTarget {

    /** `<version>1.2.3</version>` — edit the version element itself. */
    data object Literal : FixTarget

    /** `<version>${x.version}</version>` — edit the property DEFINITION, never inline. */
    data class Property(val name: String) : FixTarget

    /** No safe automated edit: managed by parent/BOM, or mixed text like `${major}.0`. */
    data object None : FixTarget

    companion object {

        private val SINGLE_PROPERTY = Regex("""^\$\{([^{}]+)}$""")

        fun of(rawVersion: String?): FixTarget {
            if (rawVersion == null) return None // parent/BOM-managed
            val trimmed = rawVersion.trim()
            SINGLE_PROPERTY.matchEntire(trimmed)?.let { match ->
                val name = match.groupValues[1]
                // project.* built-ins are not user-editable properties
                return if (name.startsWith("project.")) None else Property(name)
            }
            if ("\${" in trimmed) return None // mixed literal+property text
            if (trimmed.isEmpty()) return None
            return Literal
        }
    }
}
