package com.tampwell.staleguard.repository

/**
 * Normalizes the SCM URL formats found in real-world POMs into a browsable
 * web URL, and derives a changelog/releases page for known hosts. Pure logic.
 */
object ScmUrls {

    private val GITHUB_LIKE = Regex("""(?:github|gitlab)\.com[:/]([\w.-]+)/([\w.-]+?)(?:\.git)?(?:/.*)?$""")

    /** `scm:git:https://github.com/x/y.git` / `git@github.com:x/y.git` → https URL, or null. */
    fun webUrl(scmValue: String?): String? {
        if (scmValue.isNullOrBlank()) return null
        val cleaned = scmValue
            .removePrefix("scm:git:")
            .removePrefix("scm:svn:")
            .removePrefix("git:")
            .trim()

        GITHUB_LIKE.find(cleaned)?.let { match ->
            val host = if ("gitlab" in match.value) "gitlab.com" else "github.com"
            return "https://$host/${match.groupValues[1]}/${match.groupValues[2]}"
        }

        return cleaned.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    /** Releases page for GitHub/GitLab; the plain web URL for everything else. */
    fun changelogUrl(scmValue: String?): String? {
        val web = webUrl(scmValue) ?: return null
        return if ("github.com/" in web || "gitlab.com/" in web) {
            web.trimEnd('/') + if ("gitlab.com/" in web) "/-/releases" else "/releases"
        } else {
            web
        }
    }
}
