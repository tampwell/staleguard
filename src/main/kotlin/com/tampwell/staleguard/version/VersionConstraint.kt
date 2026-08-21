package com.tampwell.staleguard.version

/**
 * A predicate over versions, parsed from the constraint syntaxes that appear
 * in dependency policy files. One model serves them all: Staleguard's own
 * `[pins]` entries, Maven bracket ranges, and the operator style Dependabot
 * accepts for Maven/Gradle. Comparison always uses [MavenVersion] ordering,
 * never naive semver.
 *
 * parse() returns null for anything it does not understand — callers decide
 * what an unparseable constraint means for their surface (a skipped pin, an
 * ignored dependency), so this stays policy-free.
 */
sealed class VersionConstraint {

    abstract fun allows(version: MavenVersion): Boolean

    /** `2.*` / `2.x` — the version is exactly the prefix or extends it segment-wise. */
    data class Prefix(val prefix: String) : VersionConstraint() {
        override fun allows(version: MavenVersion): Boolean =
            version.value == prefix || version.value.startsWith("$prefix.")
    }

    data class Comparison(val op: Op, val bound: MavenVersion) : VersionConstraint() {
        enum class Op { LT, LE, GT, GE, EQ, NE }

        override fun allows(version: MavenVersion): Boolean = when (op) {
            Op.LT -> version < bound
            Op.LE -> version <= bound
            Op.GT -> version > bound
            Op.GE -> version >= bound
            Op.EQ -> version.compareTo(bound) == 0
            Op.NE -> version.compareTo(bound) != 0
        }
    }

    data class Exact(val version: MavenVersion) : VersionConstraint() {
        override fun allows(version: MavenVersion): Boolean = version.compareTo(this.version) == 0
    }

    /** Maven bracket range: `[1.0,2.0)`, `(,3.0]`, `[1.5]`. Null end = unbounded. */
    data class Range(
        val lower: MavenVersion?,
        val lowerInclusive: Boolean,
        val upper: MavenVersion?,
        val upperInclusive: Boolean,
    ) : VersionConstraint() {
        override fun allows(version: MavenVersion): Boolean {
            if (lower != null) {
                val cmp = version.compareTo(lower)
                if (cmp < 0 || (cmp == 0 && !lowerInclusive)) return false
            }
            if (upper != null) {
                val cmp = version.compareTo(upper)
                if (cmp > 0 || (cmp == 0 && !upperInclusive)) return false
            }
            return true
        }
    }

    /** Every member must allow (comma-AND of operator terms). */
    data class All(val terms: List<VersionConstraint>) : VersionConstraint() {
        override fun allows(version: MavenVersion): Boolean = terms.all { it.allows(version) }
    }

    /** Any member allows (union of bracket ranges: `[1,2),[3,4)`). */
    data class AnyOf(val terms: List<VersionConstraint>) : VersionConstraint() {
        override fun allows(version: MavenVersion): Boolean = terms.any { it.allows(version) }
    }

    /** Complement — how an "ignore these versions" list becomes an allowed-set. */
    data class Not(val term: VersionConstraint) : VersionConstraint() {
        override fun allows(version: MavenVersion): Boolean = !term.allows(version)
    }

    /** Renovate `/regex/` and `!/regex/` allowedVersions — tested against the version string. */
    class Matching(val regex: Regex, val negated: Boolean) : VersionConstraint() {
        override fun allows(version: MavenVersion): Boolean =
            regex.containsMatchIn(version.value) != negated

        override fun equals(other: Any?): Boolean =
            other is Matching && other.regex.pattern == regex.pattern && other.negated == negated

        override fun hashCode(): Int = regex.pattern.hashCode() * 31 + negated.hashCode()
    }

    companion object {

        private val OPERATOR_TERM = Regex("""(<=|>=|!=|=|<|>)\s*([^\s,<>=!]+)""")
        private val PREFIX_WILDCARD = Regex("""^([0-9A-Za-z]+(?:\.[0-9A-Za-z]+)*)\.[*x]$""")
        private val RANGE_SPLIT = Regex("""(?<=[\])]),\s*""")
        private val RANGE = Regex("""^([\[(])\s*([^,\[\]()]*?)\s*(?:,\s*([^,\[\]()]*?)\s*)?([])])$""")

        fun parse(text: String): VersionConstraint? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null

            if (trimmed.startsWith("[") || trimmed.startsWith("(")) {
                val ranges = RANGE_SPLIT.split(trimmed).map { parseRange(it.trim()) ?: return null }
                return ranges.singleOrNull() ?: AnyOf(ranges)
            }

            if (OPERATOR_TERM.containsMatchIn(trimmed) || "," in trimmed) {
                // Comma AND (gem style ">= 2, < 3") and space AND (npm style
                // ">=2 <3") both appear in the wild; tokenizing handles both,
                // and anything left over besides separators fails the parse.
                val matches = OPERATOR_TERM.findAll(trimmed).toList()
                if (matches.isEmpty()) return null
                val leftover = OPERATOR_TERM.replace(trimmed, "").replace(Regex("""[,\s]"""), "")
                if (leftover.isNotEmpty()) return null
                val terms = matches.map { match ->
                    val op = when (match.groupValues[1]) {
                        "<" -> Comparison.Op.LT; "<=" -> Comparison.Op.LE
                        ">" -> Comparison.Op.GT; ">=" -> Comparison.Op.GE
                        "=" -> Comparison.Op.EQ; "!=" -> Comparison.Op.NE
                        else -> return null
                    }
                    Comparison(op, MavenVersion(match.groupValues[2]))
                }
                return terms.singleOrNull() ?: All(terms)
            }

            PREFIX_WILDCARD.matchEntire(trimmed)?.let { return Prefix(it.groupValues[1]) }
            if (trimmed == "*") return null // a pin that pins nothing is a mistake, not a rule

            // A bare token is an exact version — but only if it looks like one.
            if (!trimmed.all { it.isLetterOrDigit() || it in ".-_+" }) return null
            return Exact(MavenVersion(trimmed))
        }

        private fun parseRange(text: String): Range? {
            val match = RANGE.matchEntire(text) ?: return null
            val (open, first, second, close) = match.destructured
            val lowerInclusive = open == "["
            val upperInclusive = close == "]"
            val hasComma = "," in text
            if (!hasComma) {
                // [1.5] is the exact-version range; (1.5) would exclude its only member.
                if (first.isEmpty() || !lowerInclusive || !upperInclusive) return null
                val v = MavenVersion(first)
                return Range(v, true, v, true)
            }
            val lower = first.takeIf { it.isNotEmpty() }?.let(::MavenVersion)
            val upper = second.takeIf { it.isNotEmpty() }?.let(::MavenVersion)
            if (lower == null && upper == null) return null
            return Range(lower, lowerInclusive, upper, upperInclusive)
        }
    }
}
