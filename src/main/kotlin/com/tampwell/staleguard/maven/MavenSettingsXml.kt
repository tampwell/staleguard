package com.tampwell.staleguard.maven

import com.tampwell.staleguard.repository.SecureXml
import org.w3c.dom.Element

/**
 * Read-only parser for ~/.m2/settings.xml — the servers (credentials keyed by
 * id) and every id→url pair that can resolve those ids: mirrors and profile
 * repositories. Pure logic over the XXE-hardened parser.
 *
 * Passwords wrapped in {braces} are Maven master-password encrypted; we
 * DETECT that and refuse to import them — reimplementing plexus-cipher
 * decryption would mean handling a master key, which this plugin must never
 * do. Users with encrypted passwords enter credentials manually.
 */
object MavenSettingsXml {

    data class Server(val id: String, val username: String?, val password: String?) {
        val encrypted: Boolean
            get() = password?.trim()?.let { it.startsWith("{") && it.endsWith("}") } == true
    }

    data class RepoUrl(val id: String, val url: String)

    data class Parsed(val servers: List<Server>, val repoUrls: List<RepoUrl>)

    fun parse(xml: String): Parsed = try {
        val doc = SecureXml.parse(xml, "settings.xml")
        val servers = doc.getElementsByTagName("server").toElements().mapNotNull { server ->
            val id = server.childText("id") ?: return@mapNotNull null
            Server(id, server.childText("username"), server.childText("password"))
        }
        val mirrors = doc.getElementsByTagName("mirror").toElements().mapNotNull { mirror ->
            val id = mirror.childText("id") ?: return@mapNotNull null
            val url = mirror.childText("url") ?: return@mapNotNull null
            RepoUrl(id, url)
        }
        // <repository> under <profiles>; settings.xml has no other repository tag.
        val profileRepos = doc.getElementsByTagName("repository").toElements().mapNotNull { repo ->
            val id = repo.childText("id") ?: return@mapNotNull null
            val url = repo.childText("url") ?: return@mapNotNull null
            RepoUrl(id, url)
        }
        Parsed(servers, mirrors + profileRepos)
    } catch (_: Exception) {
        Parsed(emptyList(), emptyList())
    }

    private fun org.w3c.dom.NodeList.toElements(): List<Element> =
        (0 until length).mapNotNull { item(it) as? Element }

    private fun Element.childText(tag: String): String? {
        val children = childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            if (child.tagName == tag) return child.textContent?.trim()?.takeIf { it.isNotEmpty() }
        }
        return null
    }
}
