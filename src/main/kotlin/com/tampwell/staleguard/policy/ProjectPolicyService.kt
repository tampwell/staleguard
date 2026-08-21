package com.tampwell.staleguard.policy

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.tampwell.staleguard.settings.StaleguardSettings

/**
 * The single ignore authority every surface asks. Combines the user's IDE
 * settings with the project's committed rules — `.staleguard.toml`,
 * `renovate.json` (root or .github/), and `.github/dependabot.yml` — so the
 * IDE never nags about a dependency the team's bot is configured to leave
 * alone. Parsed rules are cached per file modification stamp; the isIgnored
 * hot path (called per dependency during highlighting) is a list scan over
 * a few patterns.
 */
@Service(Service.Level.PROJECT)
class ProjectPolicyService(private val project: Project) {

    private data class CacheKey(val paths: List<String>, val stamps: List<Long>)

    private data class Parsed(
        val ignorePatterns: List<String>,
        val licensePolicy: LicensePolicy,
        val pins: List<VersionPin>,
    )

    @Volatile
    private var cached: Pair<CacheKey, Parsed>? = null

    fun isIgnored(groupId: String, artifactId: String): Boolean {
        if (StaleguardSettings.getInstance().isIgnored(groupId, artifactId)) return true
        return parsed().ignorePatterns.any { IgnoreRules.matches(it, groupId, artifactId) }
    }

    /** Committed [licenses] rules; EMPTY when the project has none. */
    fun licensePolicy(): LicensePolicy = parsed().licensePolicy

    /**
     * True when [candidate] is inside every pin that matches the coordinate —
     * the suggestion filter for "stay on 2.x" teams. Multiple matching pins
     * AND together: the most restrictive one wins. [current] feeds the
     * no-major-upgrades pins read from renovate/dependabot configs.
     */
    fun versionAllowed(
        groupId: String,
        artifactId: String,
        current: com.tampwell.staleguard.version.MavenVersion?,
        candidate: com.tampwell.staleguard.version.MavenVersion,
    ): Boolean = parsed().pins.all { !it.appliesTo(groupId, artifactId) || it.allows(current, candidate) }

    private fun parsed(): Parsed {
        val base = project.baseDir() ?: return Parsed(emptyList(), LicensePolicy.EMPTY, emptyList())
        val staleguardToml = base.findChild(".staleguard.toml")
        val sources = listOfNotNull(
            staleguardToml?.let { it to IgnoreRules::parseStaleguardToml },
            base.findChild("renovate.json")?.let { it to IgnoreRules::parseRenovate },
            base.findChild(".github")?.findChild("renovate.json")?.let { it to IgnoreRules::parseRenovate },
            base.findChild(".github")?.findChild("dependabot.yml")?.let { it to IgnoreRules::parseDependabot },
        )
        val key = CacheKey(sources.map { it.first.path }, sources.map { it.first.modificationStamp })
        cached?.takeIf { it.first == key }?.let { return it.second }

        val staleguardTomlText = staleguardToml?.let(::readText)
        val renovateTexts = listOfNotNull(
            base.findChild("renovate.json"),
            base.findChild(".github")?.findChild("renovate.json"),
        ).map(::readText)
        val dependabotText = base.findChild(".github")?.findChild("dependabot.yml")?.let(::readText)

        val renovateParity = renovateTexts.map(IgnoreRules::parseRenovatePins)
        val dependabotParity = dependabotText?.let(IgnoreRules::parseDependabotPins)

        val parsed = Parsed(
            ignorePatterns = sources.flatMap { (file, parse) -> parse(readText(file)) } +
                renovateParity.flatMap { it.second } + dependabotParity?.second.orEmpty(),
            licensePolicy = staleguardTomlText?.let(LicensePolicy::parse) ?: LicensePolicy.EMPTY,
            pins = staleguardTomlText?.let(IgnoreRules::parsePins).orEmpty() +
                renovateParity.flatMap { it.first } + dependabotParity?.first.orEmpty(),
        )
        cached = key to parsed
        return parsed
    }

    private fun readText(file: VirtualFile): String = try {
        String(file.contentsToByteArray())
    } catch (e: java.io.IOException) {
        com.intellij.openapi.diagnostic.logger<ProjectPolicyService>()
            .debug("Skipping unreadable policy file ${file.path}", e)
        ""
    }

    private fun Project.baseDir(): VirtualFile? =
        basePath?.let { com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(it) }

    companion object {
        fun getInstance(project: Project): ProjectPolicyService = project.service()
    }
}
