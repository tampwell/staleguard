package com.tampwell.staleguard.policy

import com.google.gson.JsonParser

/**
 * One ignore truth for a project, whatever tool declared it. Parsers for the
 * three places teams write dependency-ignore rules — Staleguard's own
 * committed config, renovate.json, and dependabot.yml — all reduced to
 * `group:artifact` patterns with `*` wildcards. Pure logic.
 */
object IgnoreRules {

    /** `com.internal:*`, `*:guava`, `org.slf4j:slf4j-api` — case-sensitive like Maven. */
    fun matches(pattern: String, groupId: String, artifactId: String): Boolean {
        val parts = pattern.split(':', limit = 2)
        val groupPattern = parts[0].trim()
        val artifactPattern = (parts.getOrNull(1) ?: "*").trim()
        return glob(groupPattern, groupId) && glob(artifactPattern, artifactId)
    }

    private fun glob(pattern: String, value: String): Boolean {
        if (pattern == "*") return true
        val regex = pattern.split('*').joinToString(".*") { Regex.escape(it) }
        return Regex("^$regex$").matches(value)
    }

    private val TOML_ARRAY_ENTRY = Regex(""""([^"]+)"""")

    /**
     * `.staleguard.toml`:
     * ```
     * [ignore]
     * dependencies = ["com.internal:*", "org.slf4j:slf4j-api"]
     * ```
     * Multi-line arrays supported; anything unrecognized is skipped, never fatal.
     */
    fun parseStaleguardToml(text: String): List<String> = tomlStringArray(text, "ignore", "dependencies")

    /**
     * `.staleguard.toml` version ceilings — "stay on 2.x" without losing
     * in-range updates:
     * ```
     * [pins]
     * dependencies = ["org.springframework.boot:*:2.*", "com.google.guava:guava:<33"]
     * ```
     * Last segment is the allowed-version constraint (`2.*`, `<33`, `>=2, <3`,
     * `[2.0,3.0)`, exact). Entries without three segments or with a constraint
     * that does not parse are skipped — a broken pin that silently froze a
     * dependency would be worse than one that visibly does nothing.
     */
    fun parsePins(text: String): List<VersionPin> =
        tomlStringArray(text, "pins", "dependencies").mapNotNull { entry ->
            val coordinate = entry.substringBeforeLast(':')
            if (':' !in coordinate) return@mapNotNull null
            val constraint = com.tampwell.staleguard.version.VersionConstraint.parse(entry.substringAfterLast(':'))
                ?: return@mapNotNull null
            VersionPin(coordinate, constraint)
        }

    /**
     * All string entries of `key = [ ... ]` inside `[table]`. Deliberately a
     * line scan, not a TOML parser: the config surface is flat string arrays
     * by design, and a scan can never fail on someone's creative TOML.
     */
    fun tomlStringArray(text: String, table: String, key: String): List<String> {
        val values = mutableListOf<String>()
        var inTable = false
        var inArray = false
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) {
                inTable = trimmed == "[$table]"
                inArray = false
                continue
            }
            if (!inTable) continue
            if (trimmed.startsWith(key)) inArray = true
            if (inArray) {
                TOML_ARRAY_ENTRY.findAll(trimmed).forEach { values += it.groupValues[1] }
                if (trimmed.endsWith("]")) inArray = false
            }
        }
        return values
    }

    /**
     * renovate.json: top-level `ignoreDeps`, plus `packageRules` entries with
     * `"enabled": false` (matchPackageNames exact, matchPackagePrefixes as
     * prefix wildcards). Renovate's Maven package names are already
     * `group:artifact`.
     */
    fun parseRenovate(json: String): List<String> = try {
        val root = JsonParser.parseString(json).asJsonObject
        val ignores = mutableListOf<String>()
        root.getAsJsonArray("ignoreDeps")?.forEach { dep ->
            if (dep.isJsonPrimitive) ignores += dep.asString
        }
        root.getAsJsonArray("packageRules")?.forEach { rule ->
            val obj = rule.asJsonObject
            if (obj.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean == false) {
                obj.getAsJsonArray("matchPackageNames")?.forEach { ignores += it.asString }
                obj.getAsJsonArray("matchPackagePrefixes")?.forEach { ignores += it.asString + "*" }
            }
        }
        ignores
    } catch (_: RuntimeException) {
        emptyList()
    }

    private val DEPENDABOT_NAME = Regex("""-\s+dependency-name:\s*["']?([^"'\s]+)["']?""")

    /**
     * dependabot.yml `ignore:` blocks. A line-level scan instead of a YAML
     * parser: `- dependency-name:` entries are unambiguous, and the file's
     * other content can't produce false positives for this key.
     */
    fun parseDependabot(yaml: String): List<String> =
        DEPENDABOT_NAME.findAll(yaml).map { it.groupValues[1] }.toList()
}
