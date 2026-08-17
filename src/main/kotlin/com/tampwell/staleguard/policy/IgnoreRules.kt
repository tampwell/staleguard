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
    fun parseStaleguardToml(text: String): List<String> {
        val ignores = mutableListOf<String>()
        var inIgnoreTable = false
        var inDependenciesArray = false
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) {
                inIgnoreTable = trimmed == "[ignore]"
                inDependenciesArray = false
                continue
            }
            if (!inIgnoreTable) continue
            if (trimmed.startsWith("dependencies")) inDependenciesArray = true
            if (inDependenciesArray) {
                TOML_ARRAY_ENTRY.findAll(trimmed).forEach { ignores += it.groupValues[1] }
                if (trimmed.endsWith("]")) inDependenciesArray = false
            }
        }
        return ignores
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
