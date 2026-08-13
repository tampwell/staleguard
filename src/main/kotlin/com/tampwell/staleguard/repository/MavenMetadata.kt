package com.tampwell.staleguard.repository

import com.tampwell.staleguard.version.MavenVersion
import com.tampwell.staleguard.version.isStable
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Parsed form of a repository `maven-metadata.xml`. Pure logic — no platform,
 * no network — so every quirk is unit-testable.
 *
 * Trust notes, learned from how repositories actually behave:
 *  - `<latest>` is unreliable (often includes SNAPSHOTs) — we compute our own.
 *  - `<release>` is the newest non-SNAPSHOT deploy, but can lag; we compute.
 *  - `<lastUpdated>` is the last DEPLOY time in yyyyMMddHHmmss (UTC).
 */
data class MavenMetadata(
    val groupId: String?,
    val artifactId: String?,
    val versions: List<MavenVersion>,
    /** Last deploy time (UTC) as declared by the repository, if parseable. */
    val lastUpdatedUtc: LocalDateTime?,
) {

    /** Newest version by Maven ordering, including prereleases. */
    val latest: MavenVersion? get() = versions.maxOrNull()

    /**
     * Newest stable version: no SNAPSHOT and no prerelease qualifier
     * (alpha/beta/milestone/rc and their aliases). This is what Staleguard
     * suggests by default.
     */
    val latestStable: MavenVersion? get() = versions.filter { it.isStable }.maxOrNull()

    companion object {

        private val LAST_UPDATED = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

        /**
         * Parses a maven-metadata.xml document. Throws [MetadataParseException]
         * on malformed XML; tolerates missing optional elements.
         */
        fun parse(xml: String): MavenMetadata {
            val document = try {
                newHardenedDocumentBuilderFactory().newDocumentBuilder()
                    .parse(xml.byteInputStream())
            } catch (e: Exception) {
                throw MetadataParseException("Malformed maven-metadata.xml", e)
            }

            val root = document.documentElement
                ?: throw MetadataParseException("Empty maven-metadata.xml")

            val versioning = root.firstChildElement("versioning")
            val versions = versioning
                ?.firstChildElement("versions")
                ?.childElements("version")
                ?.mapNotNull { it.textContent?.trim()?.takeIf(String::isNotEmpty) }
                ?.map(::MavenVersion)
                .orEmpty()

            val lastUpdated = versioning
                ?.firstChildElement("lastUpdated")
                ?.textContent?.trim()
                ?.let { runCatching { LocalDateTime.parse(it, LAST_UPDATED) }.getOrNull() }

            return MavenMetadata(
                groupId = root.firstChildElement("groupId")?.textContent?.trim(),
                artifactId = root.firstChildElement("artifactId")?.textContent?.trim(),
                versions = versions,
                lastUpdatedUtc = lastUpdated,
            )
        }

        fun lastUpdatedEpochSeconds(metadata: MavenMetadata): Long? =
            metadata.lastUpdatedUtc?.toEpochSecond(ZoneOffset.UTC)

        /** XXE-hardened parser: metadata comes from the network. */
        private fun newHardenedDocumentBuilderFactory(): DocumentBuilderFactory =
            DocumentBuilderFactory.newInstance().apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }

        private fun Element.firstChildElement(name: String): Element? {
            var child = firstChild
            while (child != null) {
                if (child is Element && child.tagName == name) return child
                child = child.nextSibling
            }
            return null
        }

        private fun Element.childElements(name: String): List<Element> {
            val result = mutableListOf<Element>()
            var child = firstChild
            while (child != null) {
                if (child is Element && child.tagName == name) result.add(child)
                child = child.nextSibling
            }
            return result
        }
    }
}

class MetadataParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
