package com.tampwell.staleguard.repository

/**
 * Repository URLs a project declares in its own build files — corporate
 * mirrors, JitPack, company Nexus with anonymous read. Pure text extraction;
 * the well-known defaults are excluded because the router already covers
 * them. Credentialed repositories are deliberately out of scope: Staleguard
 * never reads or stores repository credentials.
 */
object DeclaredRepositories {

    private val POM_REPO = Regex("""<repository>.*?<url>\s*(https?://[^<\s]+?)\s*</url>""", RegexOption.DOT_MATCHES_ALL)

    private val GRADLE_BLOCK = Regex("""maven\s*\{[^}]*?url\s*=?\s*(?:uri\s*\()?\s*["'](https?://[^"']+)["']""", RegexOption.DOT_MATCHES_ALL)
    private val GRADLE_CALL = Regex("""maven\s*\(\s*(?:url\s*=\s*)?["'](https?://[^"']+)["']\s*\)""")

    private val DEFAULT_HOSTS = listOf(
        "repo1.maven.org", "repo.maven.apache.org", "dl.google.com",
        "maven.google.com", "plugins.gradle.org",
    )

    fun fromPomXml(text: String): List<String> =
        POM_REPO.findAll(text).map { it.groupValues[1] }.normalize()

    fun fromGradle(text: String): List<String> =
        (GRADLE_BLOCK.findAll(text) + GRADLE_CALL.findAll(text)).map { it.groupValues[1] }.normalize()

    private fun Sequence<String>.normalize(): List<String> =
        map { it.trimEnd('/') }
            .filter { url -> DEFAULT_HOSTS.none { host -> "://$host" in url || ".$host" in url } }
            .distinct()
            .toList()
}
