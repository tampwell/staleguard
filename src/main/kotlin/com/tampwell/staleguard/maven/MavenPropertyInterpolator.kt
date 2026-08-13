package com.tampwell.staleguard.maven

/**
 * Resolves Maven `${property}` placeholders against a property map.
 *
 * Pure logic, no platform dependencies — keep it that way so it stays
 * exhaustively unit-testable. Unknown properties are left untouched
 * (mirroring Maven, which leaves unresolvable placeholders literal), and
 * expansion is depth-limited so cyclic property definitions can never hang
 * the caller.
 */
object MavenPropertyInterpolator {

    private val PLACEHOLDER = Regex("""\$\{([^{}]+)}""")

    private const val MAX_DEPTH = 10

    fun interpolate(raw: String, properties: Map<String, String>): String {
        var current = raw
        repeat(MAX_DEPTH) {
            if ('$' !in current) return current
            val next = PLACEHOLDER.replace(current) { match ->
                properties[match.groupValues[1]] ?: match.value
            }
            if (next == current) return current
            current = next
        }
        return current
    }
}
