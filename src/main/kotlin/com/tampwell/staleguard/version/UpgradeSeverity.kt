package com.tampwell.staleguard.version

/**
 * How big a jump an upgrade is, judged from the leading numeric components
 * (semver-style, tolerant of Maven's looser reality).
 */
enum class UpgradeSeverity {
    /** First numeric component changed: 1.x → 2.x. Breaking changes likely. */
    MAJOR,

    /** Second numeric component changed: 1.2.x → 1.3.x. New features, low risk. */
    MINOR,

    /** Anything past the second numeric component changed: 1.2.3 → 1.2.4. */
    PATCH,

    /** Only qualifiers differ (1.0-rc1 → 1.0) or versions aren't number-led. */
    QUALIFIER;

    companion object {

        private val LEADING_NUMBERS = Regex("""^(\d+)(?:\.(\d+))?(?:\.(\d+))?""")

        /**
         * Classifies the jump from [current] to [candidate]. Returns null when
         * [candidate] is not actually newer than [current] under Maven ordering
         * — callers should treat null as "no upgrade to offer".
         */
        fun classify(current: MavenVersion, candidate: MavenVersion): UpgradeSeverity? {
            if (candidate <= current) return null

            val from = LEADING_NUMBERS.find(current.value.trim())
            val to = LEADING_NUMBERS.find(candidate.value.trim())
            if (from == null || to == null) return QUALIFIER

            fun component(match: MatchResult, index: Int): Long =
                match.groupValues[index].takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 0L

            return when {
                component(from, 1) != component(to, 1) -> MAJOR
                component(from, 2) != component(to, 2) -> MINOR
                current.value.substringBefore('-') != candidate.value.substringBefore('-') -> PATCH
                else -> QUALIFIER
            }
        }
    }
}
