package com.tampwell.staleguard.repository

/**
 * Repository URLs a project declares in its own build files — corporate
 * mirrors, JitPack, company Nexus. Pure text extraction; the well-known
 * defaults are excluded because the router already covers them.
 *
 * Authentication is handled one layer down: [RepositoryCredentials] supplies
 * per-host Basic auth for hosts the user explicitly configured, and nothing
 * else. This extractor stays credential-blind on purpose.
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

    private val POM_REPO_BLOCK = Regex("""<repository>(.*?)</repository>""", RegexOption.DOT_MATCHES_ALL)
    private val TAG_ID = Regex("""<id>\s*([^<\s][^<]*?)\s*</id>""")
    private val TAG_URL = Regex("""<url>\s*(https?://[^<\s]+?)\s*</url>""")

    /**
     * (id, url) pairs from pom `<repository>` blocks — the map that lets a
     * settings.xml `<server>` id resolve to an actual host. Order of id and
     * url inside the block is not fixed, so both are searched independently.
     */
    fun pomRepositoriesWithIds(text: String): List<Pair<String, String>> =
        POM_REPO_BLOCK.findAll(text).mapNotNull { block ->
            val body = block.groupValues[1]
            val id = TAG_ID.find(body)?.groupValues?.get(1) ?: return@mapNotNull null
            val url = TAG_URL.find(body)?.groupValues?.get(1) ?: return@mapNotNull null
            id to url.trimEnd('/')
        }.toList()

    fun fromGradle(text: String): List<String> =
        (GRADLE_BLOCK.findAll(text) + GRADLE_CALL.findAll(text)).map { it.groupValues[1] }.normalize()

    private fun Sequence<String>.normalize(): List<String> =
        map { it.trimEnd('/') }
            .filter { url -> DEFAULT_HOSTS.none { host -> "://$host" in url || ".$host" in url } }
            .distinct()
            .toList()
}
