package com.tampwell.staleguard.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.tampwell.staleguard.repository.MavenMirrorSelector
import com.tampwell.staleguard.settings.StaleguardSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where Central lookups should go, according to ~/.m2/settings.xml mirrors —
 * cached by the file's modification time so the per-lookup cost is one stat
 * call. Runs on the lookup path (background dispatcher), never the EDT.
 * Any read or parse problem means Direct: broken settings must not take the
 * plugin offline.
 */
@Service(Service.Level.APP)
class MavenMirrorService {

    @Volatile
    private var cached: Pair<Long, MavenMirrorSelector.CentralRoute>? = null

    fun route(): MavenMirrorSelector.CentralRoute {
        if (!StaleguardSettings.getInstance().state.useMavenMirrors) {
            return MavenMirrorSelector.CentralRoute.Direct
        }
        val settingsFile = settingsFile()
        val stamp = try {
            if (Files.exists(settingsFile)) Files.getLastModifiedTime(settingsFile).toMillis() else NO_FILE
        } catch (_: Exception) {
            NO_FILE
        }
        if (stamp == NO_FILE) return MavenMirrorSelector.CentralRoute.Direct
        cached?.takeIf { it.first == stamp }?.let { return it.second }

        val route = try {
            MavenMirrorSelector.centralRoute(MavenMirrorSelector.parseMirrors(Files.readString(settingsFile)))
        } catch (_: Exception) {
            MavenMirrorSelector.CentralRoute.Direct
        }
        cached = stamp to route
        return route
    }

    private fun settingsFile(): Path = Path.of(System.getProperty("user.home"), ".m2", "settings.xml")

    companion object {
        private const val NO_FILE = -1L

        fun getInstance(): MavenMirrorService = service()
    }
}
