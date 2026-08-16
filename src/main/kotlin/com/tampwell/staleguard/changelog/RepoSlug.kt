package com.tampwell.staleguard.changelog

import com.tampwell.staleguard.repository.ScmUrls

/**
 * owner/name on a known forge, extracted from a POM scm URL — the address the
 * changelog engine fetches from. Pure logic.
 */
data class RepoSlug(val host: Host, val owner: String, val name: String) {

    enum class Host { GITHUB, GITLAB }

    /** GitHub REST releases-by-tag; GitLab public releases API. */
    fun releaseByTagUrl(tag: String): String = when (host) {
        Host.GITHUB -> "https://api.github.com/repos/$owner/$name/releases/tags/$tag"
        Host.GITLAB -> "https://gitlab.com/api/v4/projects/$owner%2F$name/releases/$tag"
    }

    /** Raw CHANGELOG.md candidates, most common name and branch first. */
    fun changelogRawUrls(): List<String> = when (host) {
        Host.GITHUB -> listOf("main", "master").flatMap { branch ->
            listOf("CHANGELOG.md", "CHANGES.md", "changelog.md").map { file ->
                "https://raw.githubusercontent.com/$owner/$name/$branch/$file"
            }
        }
        Host.GITLAB -> listOf("main", "master").map { branch ->
            "https://gitlab.com/$owner/$name/-/raw/$branch/CHANGELOG.md"
        }
    }

    companion object {
        private val SLUG = Regex("""https://(github|gitlab)\.com/([\w.-]+)/([\w.-]+)""")

        fun from(scmValue: String?): RepoSlug? {
            val web = ScmUrls.webUrl(scmValue) ?: return null
            val match = SLUG.find(web) ?: return null
            val host = if (match.groupValues[1] == "github") Host.GITHUB else Host.GITLAB
            return RepoSlug(host, match.groupValues[2], match.groupValues[3])
        }
    }
}
