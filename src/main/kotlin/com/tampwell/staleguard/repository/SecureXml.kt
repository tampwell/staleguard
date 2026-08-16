package com.tampwell.staleguard.repository

import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document

/** XXE-hardened parsing for XML that arrives from the network. */
internal object SecureXml {

    @Throws(MetadataParseException::class)
    fun parse(xml: String, what: String): Document = try {
        factory().newDocumentBuilder().parse(xml.byteInputStream())
    } catch (e: Exception) {
        throw MetadataParseException("Malformed $what", e)
    }

    fun factory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
}
