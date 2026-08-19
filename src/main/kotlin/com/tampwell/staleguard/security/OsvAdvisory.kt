package com.tampwell.staleguard.security

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tampwell.staleguard.version.MavenVersion

/**
 * One known vulnerability affecting a specific artifact version, reduced to
 * what the editor needs to say: which advisory, how bad, and where it's fixed.
 */
data class OsvAdvisory(
    /** OSV id, typically a GHSA. */
    val id: String,
    /** First CVE alias when one exists — what developers actually search for. */
    val cveId: String?,
    /** CRITICAL / HIGH / MODERATE / LOW as published; null when unrated. */
    val severity: String?,
    val summary: String?,
    /** First version at or above the queried one that no longer contains this vulnerability. */
    val fixedVersion: String?,
) {
    val displayId: String get() = cveId ?: id
    val url: String get() = "https://osv.dev/vulnerability/$id"

    /** Ordering weight: unrated sits between MODERATE and HIGH on purpose — unknown is not "low". */
    val severityRank: Int
        get() = when (severity?.uppercase()) {
            "CRITICAL" -> 4
            "HIGH" -> 3
            null -> 2
            "MODERATE", "MEDIUM" -> 1
            else -> 0
        }
}

/**
 * Parses OSV api `v1/query` responses. Pure logic, no I/O.
 *
 * The server already filters to vulnerabilities affecting the queried version;
 * the range walk below only exists to pick the right `fixed` version for OUR
 * version (one advisory often carries several introduced/fixed windows, e.g.
 * log4j-core has a 2.x window fixed in 2.16.0 and a 1.x window fixed in
 * 2.12.2).
 */
object OsvParser {

    fun parse(json: String, packageName: String, version: MavenVersion): List<OsvAdvisory> {
        val root = JsonParser.parseString(json).asJsonObject
        val vulns = root.getAsJsonArray("vulns") ?: return emptyList()
        return vulns.mapNotNull { element ->
            val vuln = element.asJsonObject
            val id = vuln.get("id")?.asString ?: return@mapNotNull null
            OsvAdvisory(
                id = id,
                cveId = vuln.getAsJsonArray("aliases")
                    ?.map { it.asString }
                    ?.firstOrNull { it.startsWith("CVE-") },
                severity = vuln.getAsJsonObject("database_specific")
                    ?.get("severity")?.takeIf { it.isJsonPrimitive }?.asString,
                summary = vuln.get("summary")?.takeIf { it.isJsonPrimitive }?.asString,
                fixedVersion = fixedVersionFor(vuln, packageName, version),
            )
        }
    }

    private fun fixedVersionFor(vuln: JsonObject, packageName: String, version: MavenVersion): String? {
        val affected = vuln.getAsJsonArray("affected") ?: return null
        for (entry in affected) {
            val obj = entry.asJsonObject
            val pkg = obj.getAsJsonObject("package") ?: continue
            if (pkg.get("ecosystem")?.asString != "Maven") continue
            if (pkg.get("name")?.asString != packageName) continue
            for (range in obj.getAsJsonArray("ranges") ?: continue) {
                val rangeObj = range.asJsonObject
                if (rangeObj.get("type")?.asString != "ECOSYSTEM") continue
                var introduced: MavenVersion? = null
                for (event in rangeObj.getAsJsonArray("events") ?: continue) {
                    val eventObj = event.asJsonObject
                    eventObj.get("introduced")?.asString?.let { introduced = MavenVersion(it) }
                    val fixed = eventObj.get("fixed")?.asString ?: continue
                    val from = introduced
                    if (from != null && version >= from && version < MavenVersion(fixed)) return fixed
                }
            }
        }
        return null
    }
}
