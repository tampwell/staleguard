package com.tampwell.staleguard.changelog

import com.google.gson.JsonParser
import com.tampwell.staleguard.repository.FetchResult
import com.tampwell.staleguard.repository.MavenRepositoryClient

/**
 * Fetches "what changed between my version and the suggested one" with a
 * hard request budget. Anonymous GitHub API allows 60 requests/hour, so the
 * strategy is: one CHANGELOG.md fetch usually answers for EVERY skipped
 * version; only when no changelog file exists do we spend up to two more
 * requests on the suggested version's release notes. Results are immutable
 * per (coordinates, from, to) — callers cache them forever.
 */
class ChangelogEngine(private val client: MavenRepositoryClient) {

    data class VersionNotes(val version: String, val body: String)

    data class Summary(
        val notes: List<VersionNotes>,
        val signals: BreakingSignals.Scan,
        val sourceUrl: String,
        /** Versions in the skipped range the source had nothing for. */
        val uncovered: List<String>,
    )

    /** At most [MAX_REQUESTS] HTTP calls per invocation, whatever happens. */
    fun summarize(
        scmValue: String?,
        artifactId: String?,
        currentVersion: String,
        suggestedVersion: String,
        allVersions: List<String>,
    ): Summary? {
        val slug = RepoSlug.from(scmValue) ?: return null
        val range = ReleaseTags.skippedRange(currentVersion, suggestedVersion, allVersions)
            .takeLast(MAX_RANGE)
        if (range.isEmpty()) return null
        var requests = 0

        // One changelog file covers all versions — always worth the first
        // tries, but the tag fallback must keep budget of its own or six
        // missing-changelog 404s starve it entirely.
        for (url in slug.changelogRawUrls()) {
            if (requests >= MAX_REQUESTS - TAG_RESERVE) break
            requests++
            val body = fetch(url) ?: continue
            val notes = range.mapNotNull { version ->
                ChangelogParser.sectionFor(body, version)?.let { VersionNotes(version, it) }
            }
            if (notes.isNotEmpty()) {
                val combined = notes.joinToString("\n") { it.body }
                return Summary(notes, BreakingSignals.scan(combined), url, range - notes.map { it.version }.toSet())
            }
        }

        // Fall back to the suggested version's release notes only.
        for (tag in ReleaseTags.candidates(suggestedVersion, artifactId)) {
            if (requests >= MAX_REQUESTS) break
            requests++
            val json = fetch(slug.releaseByTagUrl(tag)) ?: continue
            val body = releaseBody(json) ?: continue
            return Summary(
                listOf(VersionNotes(suggestedVersion, body)),
                BreakingSignals.scan(body),
                slug.releaseByTagUrl(tag),
                range - suggestedVersion,
            )
        }
        return null
    }

    private fun fetch(url: String): String? =
        when (val result = client.fetchMetadata(url, null)) {
            is FetchResult.Fetched -> result.body
            else -> null
        }

    /** The "body" field of a GitHub/GitLab release JSON (GitLab: description). */
    internal fun releaseBody(json: String): String? = try {
        val obj = JsonParser.parseString(json).asJsonObject
        val body = obj.get("body") ?: obj.get("description")
        body?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifEmpty { null }
    } catch (_: RuntimeException) {
        null
    }

    companion object {
        const val MAX_REQUESTS = 4
        const val TAG_RESERVE = 2
        const val MAX_RANGE = 15
    }
}
