package com.tampwell.staleguard.settings

import com.tampwell.staleguard.maven.MavenSettingsXml
import com.tampwell.staleguard.repository.RepositoryCredentials
import java.nio.file.Files
import java.nio.file.Path

/**
 * Turns ~/.m2/settings.xml servers into import candidates by resolving each
 * server id to a host: mirrors and profile repositories from settings.xml
 * itself, plus `<repository>` ids from the open project's pom files (the
 * "repos in the pom, creds in settings" convention).
 *
 * Pure candidate assembly; the only I/O is reading the settings file, and
 * nothing here ever writes or transmits — importing is the user ticking
 * checkboxes in the dialog.
 */
object SettingsXmlImport {

    data class Candidate(
        val serverId: String,
        val username: String?,
        val host: String?,
        val password: String?,
        val encrypted: Boolean,
    ) {
        val importable: Boolean
            get() = host != null && !username.isNullOrEmpty() && !password.isNullOrEmpty() && !encrypted
    }

    fun defaultSettingsFile(): Path = Path.of(System.getProperty("user.home"), ".m2", "settings.xml")

    fun readSettingsFile(path: Path = defaultSettingsFile()): String? = try {
        if (Files.exists(path)) Files.readString(path) else null
    } catch (_: Exception) {
        null
    }

    /**
     * @param projectRepoIds (id, url) pairs from the project's pom files;
     *   they win over settings.xml entries on id collision because the
     *   project is the more specific declaration.
     */
    fun candidates(settingsXml: String, projectRepoIds: List<Pair<String, String>>): List<Candidate> {
        val parsed = MavenSettingsXml.parse(settingsXml)
        val urlById = buildMap {
            parsed.repoUrls.forEach { putIfAbsent(it.id, it.url) }
            projectRepoIds.forEach { (id, url) -> put(id, url) }
        }
        return parsed.servers.map { server ->
            Candidate(
                serverId = server.id,
                username = server.username,
                host = urlById[server.id]?.let(RepositoryCredentials::hostOf),
                password = server.password,
                encrypted = server.encrypted,
            )
        }
    }
}
