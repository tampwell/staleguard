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

    @Volatile
    private var cached: Pair<CacheKey, List<String>>? = null

    fun isIgnored(groupId: String, artifactId: String): Boolean {
        if (StaleguardSettings.getInstance().isIgnored(groupId, artifactId)) return true
        return projectPatterns().any { IgnoreRules.matches(it, groupId, artifactId) }
    }

    private fun projectPatterns(): List<String> {
        val base = project.baseDir() ?: return emptyList()
        val sources = listOfNotNull(
            base.findChild(".staleguard.toml")?.let { it to IgnoreRules::parseStaleguardToml },
            base.findChild("renovate.json")?.let { it to IgnoreRules::parseRenovate },
            base.findChild(".github")?.findChild("renovate.json")?.let { it to IgnoreRules::parseRenovate },
            base.findChild(".github")?.findChild("dependabot.yml")?.let { it to IgnoreRules::parseDependabot },
        )
        val key = CacheKey(sources.map { it.first.path }, sources.map { it.first.modificationStamp })
        cached?.takeIf { it.first == key }?.let { return it.second }

        val patterns = sources.flatMap { (file, parse) -> parse(readText(file)) }
        cached = key to patterns
        return patterns
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
