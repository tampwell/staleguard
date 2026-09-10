package com.tampwell.staleguard.gradle

/**
 * Parser and drift detector for Gradle dependency-locking files. Pure text
 * logic in the [VersionCatalog] mold: no Gradle API, no PSI, exhaustively
 * testable, and anything unparseable is skipped, never fatal.
 *
 * Two formats exist in the wild. The single-file format (Gradle 6+) lives at
 * `gradle.lockfile` (also `settings-gradle.lockfile` and
 * `buildscript-gradle.lockfile`) with lines of
 * `group:artifact:version=configuration,configuration`. The legacy format is
 * one file per configuration under `gradle/dependency-locks/<name>.lockfile`
 * with bare `group:artifact:version` lines - the configuration comes from the
 * file name, passed here as [parse]'s fallback.
 */
object Lockfile {

    data class Locked(
        val group: String,
        val name: String,
        val version: String,
        val configurations: List<String>,
    )

    /** A declaration the build files govern, for drift comparison. */
    data class Declared(
        val group: String,
        val name: String,
        val version: String,
    )

    /**
     * The lockfile holds one version, the declarations another. Reported only
     * when both sides are concrete and disagree - that is the forgot-to-relock
     * footgun this feature exists for.
     */
    data class Drift(
        val group: String,
        val name: String,
        val lockedVersion: String,
        val declaredVersion: String,
    )

    val SINGLE_FILE_NAMES = setOf("gradle.lockfile", "settings-gradle.lockfile", "buildscript-gradle.lockfile")

    private val COORDINATE = Regex("""^([^:#=\s]+):([^:=\s]+):([^:=\s]+?)(?:=(.*))?$""")

    fun parse(text: String, fallbackConfiguration: String? = null): List<Locked> {
        val locked = mutableListOf<Locked>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) continue
            // "empty=confA,confB" records configurations that locked nothing.
            if (line.startsWith("empty=")) continue
            val match = COORDINATE.matchEntire(line) ?: continue
            val (group, name, version, tail) = match.destructured
            val configurations = when {
                tail.isNotEmpty() -> tail.split(',').map(String::trim).filter(String::isNotEmpty)
                fallbackConfiguration != null -> listOf(fallbackConfiguration)
                else -> emptyList()
            }
            locked += Locked(group, name, version, configurations)
        }
        return locked
    }

    /**
     * A dynamic declaration (`1.+`, `latest.release`, ranges) never drifts:
     * pinning those is the lockfile's entire purpose, so the lock IS the
     * declared intent.
     */
    fun isDynamic(version: String): Boolean =
        version.endsWith('+') ||
            version.startsWith("latest.") ||
            version.any { it in "[]()," }

    fun drift(locked: List<Locked>, declared: List<Declared>): List<Drift> {
        val declaredByCoordinate = declared
            .filterNot { isDynamic(it.version) }
            .associateBy { it.group to it.name }
        val drifts = mutableListOf<Drift>()
        val seen = mutableSetOf<Pair<String, String>>()
        for (lock in locked) {
            val key = lock.group to lock.name
            if (!seen.add(key)) continue
            val declaration = declaredByCoordinate[key] ?: continue
            if (declaration.version != lock.version) {
                drifts += Drift(lock.group, lock.name, lock.version, declaration.version)
            }
        }
        return drifts
    }
}
