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
     *
     * A rule scoped by `matchUpdateTypes` disables only those update types —
     * it is a pin ([parseRenovatePins]), NOT a full ignore.
     */
    fun parseRenovate(json: String): List<String> = try {
        val root = JsonParser.parseString(json).asJsonObject
        val ignores = mutableListOf<String>()
        root.getAsJsonArray("ignoreDeps")?.forEach { dep ->
            if (dep.isJsonPrimitive) ignores += dep.asString
        }
        root.getAsJsonArray("packageRules")?.forEach { rule ->
            val obj = rule.asJsonObject
            val scopedToUpdateTypes = obj.getAsJsonArray("matchUpdateTypes")?.size() ?: 0
            if (obj.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean == false && scopedToUpdateTypes == 0) {
                renovateRuleNames(obj).forEach { ignores += it }
            }
        }
        ignores
    } catch (_: RuntimeException) {
        emptyList()
    }

    /**
     * Renovate rules that cap versions rather than disable the dependency:
     * `allowedVersions` (regex, Maven range, npm-style operators, or exact),
     * `matchUpdateTypes:["major"]` + `enabled:false`, and the older
     * `"major": {"enabled": false}` idiom.
     *
     * Returns pins plus the names whose cap could not be evaluated (invalid
     * regex, Handlebars templates) — those become full ignores: when we
     * cannot tell which versions the team allows, suggesting freely would
     * betray the committed config. Renovate's later-rule-wins merge is
     * approximated as AND across matching pins; teams with two competing
     * caps for one dependency are beyond a warm-cache IDE heuristic.
     */
    fun parseRenovatePins(json: String): Pair<List<VersionPin>, List<String>> = try {
        val root = JsonParser.parseString(json).asJsonObject
        val pins = mutableListOf<VersionPin>()
        val extraIgnores = mutableListOf<String>()
        root.getAsJsonArray("packageRules")?.forEach { rule ->
            val obj = rule.asJsonObject
            val names = renovateRuleNames(obj)
            if (names.isEmpty()) return@forEach

            val disabled = obj.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean == false
            val updateTypes = obj.getAsJsonArray("matchUpdateTypes")
                ?.mapNotNull { it.takeIf(com.google.gson.JsonElement::isJsonPrimitive)?.asString }
                .orEmpty()
            val majorObjectDisabled = obj.getAsJsonObject("major")
                ?.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean == false
            val blockMajors = majorObjectDisabled || (disabled && "major" in updateTypes)

            val allowedSpec = obj.get("allowedVersions")?.takeIf { it.isJsonPrimitive }?.asString
            val constraint = allowedSpec?.let { spec ->
                renovateAllowedVersions(spec) ?: run {
                    extraIgnores += names
                    return@forEach
                }
            }

            if (constraint != null || blockMajors) {
                names.forEach { pins += VersionPin(it, constraint, blockMajors) }
            }
        }
        pins to extraIgnores
    } catch (_: RuntimeException) {
        emptyList<VersionPin>() to emptyList()
    }

    /** Rule names in our pattern grammar: exact, `x**`→`x*`; regex names are skipped. */
    private fun renovateRuleNames(rule: com.google.gson.JsonObject): List<String> {
        val names = mutableListOf<String>()
        rule.getAsJsonArray("matchPackageNames")?.forEach { element ->
            val name = element.takeIf(com.google.gson.JsonElement::isJsonPrimitive)?.asString ?: return@forEach
            if (name.startsWith("/") || name.startsWith("!")) return@forEach
            names += name.replace("**", "*")
        }
        rule.getAsJsonArray("matchPackagePrefixes")?.forEach { element ->
            val prefix = element.takeIf(com.google.gson.JsonElement::isJsonPrimitive)?.asString ?: return@forEach
            names += "$prefix*"
        }
        return names
    }

    private fun renovateAllowedVersions(spec: String): com.tampwell.staleguard.version.VersionConstraint? {
        if ("{{" in spec) return null // Handlebars template — computed at renovate runtime, not evaluable here
        val negated = spec.startsWith("!/")
        if (negated || spec.startsWith("/")) {
            val body = spec.removePrefix("!").removePrefix("/")
            val caseInsensitive = body.endsWith("/i")
            val pattern = body.removeSuffix("/i").removeSuffix("/")
            val regex = try {
                if (caseInsensitive) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
            } catch (_: RuntimeException) {
                return null
            }
            return com.tampwell.staleguard.version.VersionConstraint.Matching(regex, negated)
        }
        return com.tampwell.staleguard.version.VersionConstraint.parse(spec)
    }

    private val DEPENDABOT_NAME = Regex("""-\s+dependency-name:\s*["']?([^"'\s]+)["']?""")
    private val QUOTED_OR_BARE = Regex(""""([^"]+)"|'([^']+)'|([^\s,\[\]"']+)""")

    private data class DependabotEntry(
        val name: String,
        val versions: MutableList<String> = mutableListOf(),
        val updateTypes: MutableList<String> = mutableListOf(),
    )

    /**
     * dependabot.yml `ignore:` blocks, entry-aware. A line-level scan instead
     * of a YAML parser: `- dependency-name:` starts an entry; `versions:` and
     * `update-types:` lines (inline or multi-line arrays) attach to it until
     * the next entry begins.
     *
     * An entry WITHOUT conditions ignores the dependency outright (that is
     * what it means to dependabot). An entry WITH conditions does not — it
     * becomes a [VersionPin] via [parseDependabotPins].
     */
    fun parseDependabot(yaml: String): List<String> =
        dependabotEntries(yaml)
            .filter { it.versions.isEmpty() && it.updateTypes.isEmpty() }
            .map { it.name }

    /**
     * The conditioned dependabot entries as pins. Version strings follow what
     * dependabot actually accepts for Maven/Gradle: bare exact versions,
     * Maven bracket ranges, and gem-style operator lists — all covered by
     * [com.tampwell.staleguard.version.VersionConstraint]. A version string
     * that does not parse makes the whole entry a full ignore instead — when
     * we cannot tell WHICH versions the team meant to block, suggesting
     * freely would betray their stated intent.
     */
    fun parseDependabotPins(yaml: String): Pair<List<VersionPin>, List<String>> {
        val pins = mutableListOf<VersionPin>()
        val extraIgnores = mutableListOf<String>()
        for (entry in dependabotEntries(yaml)) {
            if (entry.versions.isEmpty() && entry.updateTypes.isEmpty()) continue
            val parsedRanges = entry.versions.map { spec ->
                com.tampwell.staleguard.version.VersionConstraint.parse(spec)
                    ?: run { extraIgnores += entry.name; null }
            }
            if (parsedRanges.any { it == null }) continue
            val blockMajors = entry.updateTypes.any { it.endsWith("semver-major", ignoreCase = true) }
            val constraint = parsedRanges.filterNotNull().takeIf { it.isNotEmpty() }?.let { ranges ->
                com.tampwell.staleguard.version.VersionConstraint.Not(
                    ranges.singleOrNull() ?: com.tampwell.staleguard.version.VersionConstraint.AnyOf(ranges),
                )
            }
            if (constraint != null || blockMajors) {
                pins += VersionPin(entry.name, constraint, blockMajors)
            }
        }
        return pins to extraIgnores
    }

    private fun dependabotEntries(yaml: String): List<DependabotEntry> {
        val entries = mutableListOf<DependabotEntry>()
        var current: DependabotEntry? = null
        var collecting: MutableList<String>? = null
        for (line in yaml.lineSequence()) {
            val trimmed = line.trim()
            val nameMatch = DEPENDABOT_NAME.find(line)
            if (nameMatch != null) {
                current = DependabotEntry(nameMatch.groupValues[1]).also(entries::add)
                collecting = null
                continue
            }
            val entry = current ?: continue
            when {
                trimmed.startsWith("versions:") -> {
                    collecting = entry.versions
                    collectArrayValues(trimmed.removePrefix("versions:"), collecting)
                    if ("]" in trimmed) collecting = null
                }
                trimmed.startsWith("update-types:") -> {
                    collecting = entry.updateTypes
                    collectArrayValues(trimmed.removePrefix("update-types:"), collecting)
                    if ("]" in trimmed) collecting = null
                }
                collecting != null && (trimmed.startsWith("-") || trimmed.startsWith("\"") || trimmed == "]") -> {
                    collectArrayValues(trimmed.removePrefix("-"), collecting)
                    if (trimmed.endsWith("]")) collecting = null
                }
                else -> collecting = null
            }
        }
        return entries
    }

    private fun collectArrayValues(fragment: String, into: MutableList<String>?) {
        if (into == null) return
        val body = fragment.trim().removePrefix("[").removeSuffix("]").trim()
        if (body.isEmpty()) return
        // Bracket ranges contain commas; quoted strings keep them intact, and
        // dependabot's own docs always quote version conditions.
        QUOTED_OR_BARE.findAll(body).forEach { match ->
            val value = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return@forEach
            into += value
        }
    }
}
