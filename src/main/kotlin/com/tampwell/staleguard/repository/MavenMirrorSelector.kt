package com.tampwell.staleguard.repository

import org.w3c.dom.Element

/**
 * Maven `<mirrors>` semantics, faithfully — matching is by repository ID,
 * never URL, using the same two-pass algorithm as Maven's own
 * DefaultMirrorSelector: an exact whole-string `mirrorOf` match wins over any
 * pattern match regardless of declaration order; within a pass the first
 * declared mirror wins; comma segments are NOT trimmed (`!repo1, *` matches
 * nothing — that is Maven's documented behavior, not a bug here); negation
 * accepts literal ids only.
 *
 * Scope: Staleguard routes its CENTRAL lookups through a matching mirror so
 * Central-blocked enterprises stop seeing a silently broken plugin. Only
 * ~/.m2/settings.xml is read (the installation's global settings file is a
 * documented non-goal), and only the `central` repository id is routed —
 * Google Maven and the Plugin Portal have no id in a Maven build, and Gradle
 * does not read Maven mirrors at all.
 */
object MavenMirrorSelector {

    data class MavenMirror(val id: String, val mirrorOf: String, val url: String, val blocked: Boolean)

    data class Repo(val id: String, val url: String)

    /** The built-in Central repository exactly as the Maven super-POM declares it. */
    val CENTRAL = Repo("central", "https://repo.maven.apache.org/maven2")

    sealed class CentralRoute {
        object Direct : CentralRoute()
        data class Via(val url: String, val mirrorId: String) : CentralRoute()
        data class Blocked(val mirrorId: String) : CentralRoute()
    }

    fun parseMirrors(settingsXml: String): List<MavenMirror> = try {
        val doc = SecureXml.parse(settingsXml, "settings.xml")
        (0 until doc.getElementsByTagName("mirror").length)
            .mapNotNull { doc.getElementsByTagName("mirror").item(it) as? Element }
            .mapNotNull { mirror ->
                MavenMirror(
                    id = mirror.childText("id") ?: return@mapNotNull null,
                    mirrorOf = mirror.childText("mirrorOf") ?: return@mapNotNull null,
                    url = mirror.childText("url") ?: return@mapNotNull null,
                    blocked = mirror.childText("blocked") == "true",
                )
            }
    } catch (_: Exception) {
        emptyList()
    }

    fun centralRoute(mirrors: List<MavenMirror>): CentralRoute {
        val mirror = select(mirrors, CENTRAL) ?: return CentralRoute.Direct
        return if (mirror.blocked) {
            CentralRoute.Blocked(mirror.id)
        } else {
            CentralRoute.Via(mirror.url.trimEnd('/'), mirror.id)
        }
    }

    fun select(mirrors: List<MavenMirror>, repo: Repo): MavenMirror? =
        mirrors.firstOrNull { it.mirrorOf == repo.id }
            ?: mirrors.firstOrNull { matchesPattern(it.mirrorOf, repo) }

    private fun matchesPattern(pattern: String, repo: Repo): Boolean {
        var result = false
        for (segment in pattern.split(',')) { // no trimming, per Maven
            when {
                segment.startsWith("!") && segment.length > 1 ->
                    if (segment.substring(1) == repo.id) return false
                segment == repo.id -> return true
                segment == "*" -> result = true
                segment == "external:*" && isExternal(repo.url) -> result = true
                segment == "external:http:*" && isExternalHttp(repo.url) -> result = true
            }
        }
        return result
    }

    private fun protocolOf(url: String): String = url.substringBefore(":", "").lowercase()

    private fun hostOf(url: String): String = try {
        java.net.URI(url).host.orEmpty().lowercase()
    } catch (_: Exception) {
        ""
    }

    /** Maven's localhost test is exact-string on purpose — [::1] counts as external. */
    private fun isLocal(url: String): Boolean = hostOf(url) in setOf("localhost", "127.0.0.1")

    private fun isExternal(url: String): Boolean = !isLocal(url) && protocolOf(url) != "file"

    private fun isExternalHttp(url: String): Boolean =
        !isLocal(url) && protocolOf(url) in setOf("http", "dav", "dav:http", "dav+http")

    private fun Element.childText(tag: String): String? {
        val children = childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            if (child.tagName == tag) return child.textContent?.trim()?.takeIf { it.isNotEmpty() }
        }
        return null
    }
}
