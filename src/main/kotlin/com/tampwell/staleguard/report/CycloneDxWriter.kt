package com.tampwell.staleguard.report

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.tampwell.staleguard.security.OsvAdvisory
import java.time.Instant

/**
 * Serializes the dependency snapshot as a CycloneDX 1.5 JSON BOM — the format
 * Dependency-Track, Grype, and enterprise compliance pipelines ingest.
 * Spec 1.5 over 1.6 on purpose: we use nothing 1.6-only, and 1.5 is what the
 * widest range of deployed consumers accepts.
 *
 * Pure string logic; the caller supplies the timestamp and serial so this
 * stays deterministic under test. Vulnerability data is whatever the warm
 * OSV cache holds — an SBOM from Staleguard reflects what the editor shows,
 * it does not trigger new network lookups.
 */
object CycloneDxWriter {

    data class Component(
        val groupId: String,
        val artifactId: String,
        val version: String,
        val licenses: List<String> = emptyList(),
        val advisories: List<OsvAdvisory> = emptyList(),
    ) {
        val purl: String get() = "pkg:maven/${encode(groupId)}/${encode(artifactId)}@${encode(version)}"
    }

    fun write(
        projectName: String,
        toolVersion: String,
        components: List<Component>,
        serialUuid: String,
        timestampMillis: Long,
    ): String {
        // Same g:a:v can be declared in several modules; a BOM lists it once.
        val unique = components.distinctBy { it.purl }.sortedBy { it.purl }

        val root = JsonObject()
        root.addProperty("bomFormat", "CycloneDX")
        root.addProperty("specVersion", "1.5")
        root.addProperty("serialNumber", "urn:uuid:$serialUuid")
        root.addProperty("version", 1)

        val metadata = JsonObject()
        metadata.addProperty("timestamp", Instant.ofEpochMilli(timestampMillis).toString())
        val tool = JsonObject()
        tool.addProperty("vendor", "Tampwell")
        tool.addProperty("name", "Staleguard")
        tool.addProperty("version", toolVersion)
        metadata.add("tools", JsonArray().apply { add(tool) })
        val subject = JsonObject()
        subject.addProperty("type", "application")
        subject.addProperty("name", projectName)
        metadata.add("component", subject)
        root.add("metadata", metadata)

        val componentArray = JsonArray()
        for (component in unique) {
            val json = JsonObject()
            json.addProperty("type", "library")
            json.addProperty("bom-ref", component.purl)
            json.addProperty("group", component.groupId)
            json.addProperty("name", component.artifactId)
            json.addProperty("version", component.version)
            json.addProperty("purl", component.purl)
            if (component.licenses.isNotEmpty()) {
                val licenseArray = JsonArray()
                for (name in component.licenses) {
                    val wrapper = JsonObject()
                    val license = JsonObject()
                    license.addProperty("name", name)
                    wrapper.add("license", license)
                    licenseArray.add(wrapper)
                }
                json.add("licenses", licenseArray)
            }
            componentArray.add(json)
        }
        root.add("components", componentArray)

        val vulnerabilityArray = vulnerabilities(unique)
        if (!vulnerabilityArray.isEmpty) root.add("vulnerabilities", vulnerabilityArray)

        return GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root)
    }

    /** One entry per advisory id, with `affects` collecting every component that carries it. */
    private fun vulnerabilities(components: List<Component>): JsonArray {
        val affectedBy = linkedMapOf<String, Pair<OsvAdvisory, MutableList<String>>>()
        for (component in components) {
            for (advisory in component.advisories) {
                affectedBy.getOrPut(advisory.id) { advisory to mutableListOf() }.second += component.purl
            }
        }
        val array = JsonArray()
        for ((advisory, refs) in affectedBy.values) {
            val json = JsonObject()
            json.addProperty("id", advisory.id)
            val source = JsonObject()
            source.addProperty("name", "OSV")
            source.addProperty("url", advisory.url)
            json.add("source", source)
            advisory.cveId?.let { cve ->
                val reference = JsonObject()
                reference.addProperty("id", cve)
                val cveSource = JsonObject()
                cveSource.addProperty("name", "NVD")
                cveSource.addProperty("url", "https://nvd.nist.gov/vuln/detail/$cve")
                reference.add("source", cveSource)
                json.add("references", JsonArray().apply { add(reference) })
            }
            val rating = JsonObject()
            rating.addProperty("severity", cycloneDxSeverity(advisory.severity))
            json.add("ratings", JsonArray().apply { add(rating) })
            advisory.summary?.let { json.addProperty("description", it) }
            val affects = JsonArray()
            for (ref in refs.distinct()) {
                val affected = JsonObject()
                affected.addProperty("ref", ref)
                affects.add(affected)
            }
            json.add("affects", affects)
            array.add(json)
        }
        return array
    }

    /** CycloneDX rating enum: critical / high / medium / low / info / none / unknown. */
    private fun cycloneDxSeverity(osvSeverity: String?): String = when (osvSeverity?.uppercase()) {
        "CRITICAL" -> "critical"
        "HIGH" -> "high"
        "MODERATE", "MEDIUM" -> "medium"
        "LOW" -> "low"
        else -> "unknown"
    }

    /** purl segment encoding: unreserved characters pass, everything else percent-encodes. */
    private fun encode(segment: String): String = buildString {
        for (byte in segment.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt().toChar()
            if (c.isLetterOrDigit() && c.code < 128 || c in ".-_~") append(c)
            else append('%').append("%02X".format(byte.toInt() and 0xFF))
        }
    }
}
