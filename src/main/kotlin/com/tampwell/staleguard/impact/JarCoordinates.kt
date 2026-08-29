package com.tampwell.staleguard.impact

import com.tampwell.staleguard.repository.Coordinates
import java.nio.file.Path

/**
 * Recovers Maven coordinates from where a jar sits on disk. Both cache
 * layouts encode them in the path; anything else returns null, and a null
 * here means a finding gets NO fix suggestion — never a guessed one.
 */
object JarCoordinates {

    data class Identified(val coordinates: Coordinates, val version: String)

    private val GRADLE = Regex(
        """[/\\]modules-2[/\\]files-2\.1[/\\]([^/\\]+)[/\\]([^/\\]+)[/\\]([^/\\]+)[/\\][0-9a-f]{20,}[/\\][^/\\]+\.jar$""",
    )

    // .../repository/<group/as/dirs>/<artifact>/<version>/<artifact>-<version>[-classifier].jar
    private val MAVEN = Regex(
        """[/\\]repository[/\\](.+)[/\\]([^/\\]+)[/\\]([^/\\]+)[/\\]\2-\3(?:-[^/\\]+)?\.jar$""",
    )

    fun identify(jar: Path): Identified? {
        val text = jar.toString()
        GRADLE.find(text)?.let { m ->
            return Identified(Coordinates(m.groupValues[1], m.groupValues[2]), m.groupValues[3])
        }
        MAVEN.find(text)?.let { m ->
            return Identified(
                Coordinates(m.groupValues[1].replace('/', '.').replace('\\', '.'), m.groupValues[2]),
                m.groupValues[3],
            )
        }
        return null
    }
}
