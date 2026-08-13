package com.tampwell.staleguard.repository

import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The metadata Staleguard extracts from an artifact's .pom: licenses, SCM web
 * URL, and description. Parsed from the same GET that yields the release date
 * — no extra requests. Pure logic, XXE-hardened, unit-tested.
 */
data class PomInfo(
    val licenses: List<String> = emptyList(),
    val scmUrl: String? = null,
    val description: String? = null,
) {
    companion object {

        val EMPTY = PomInfo()

        fun parse(xml: String): PomInfo {
            val document = try {
                hardenedFactory().newDocumentBuilder().parse(xml.byteInputStream())
            } catch (_: Exception) {
                return EMPTY
            }
            val root = document.documentElement ?: return EMPTY

            val licenses = root.child("licenses")
                ?.children("license")
                ?.mapNotNull { it.child("name")?.textContent?.trim()?.takeIf(String::isNotEmpty) }
                .orEmpty()

            val scm = root.child("scm")
            val scmUrl = listOfNotNull(
                scm?.child("url")?.textContent?.trim(),
                scm?.child("connection")?.textContent?.trim(),
                scm?.child("developerConnection")?.textContent?.trim(),
                root.child("url")?.textContent?.trim(),
            ).firstOrNull { it.isNotEmpty() }

            val description = root.child("description")?.textContent?.trim()?.takeIf(String::isNotEmpty)

            return PomInfo(licenses, scmUrl, description)
        }

        /** Heuristic copyleft flag — "verify compatibility", not legal advice. */
        fun isCopyleft(licenseName: String): Boolean {
            val upper = licenseName.uppercase()
            // Acronym forms (GPL/LGPL/AGPL/SSPL) AND spelled-out forms
            // ("GNU General Public License") — the long form does not contain
            // the acronym as a substring.
            return "GPL" in upper || "SSPL" in upper ||
                "GENERAL PUBLIC LICENSE" in upper || "SERVER SIDE PUBLIC LICENSE" in upper
        }

        private fun hardenedFactory(): DocumentBuilderFactory =
            DocumentBuilderFactory.newInstance().apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }

        private fun Element.child(name: String): Element? {
            var node = firstChild
            while (node != null) {
                if (node is Element && node.tagName == name) return node
                node = node.nextSibling
            }
            return null
        }

        private fun Element.children(name: String): List<Element> {
            val result = mutableListOf<Element>()
            var node = firstChild
            while (node != null) {
                if (node is Element && node.tagName == name) result.add(node)
                node = node.nextSibling
            }
            return result
        }
    }
}

/** What one .pom GET yields: the header date plus the parsed body. */
data class PomDetails(val lastModifiedMillis: Long?, val info: PomInfo)
