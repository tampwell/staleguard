package com.tampwell.staleguard.repository

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * What Staleguard knows about one artifact, as persisted between IDE sessions.
 * All timestamps are epoch millis.
 */
data class CachedArtifact(
    @SerializedName("schema") val schema: Int = SCHEMA_VERSION,
    @SerializedName("group") val groupId: String,
    @SerializedName("artifact") val artifactId: String,
    @SerializedName("versions") val versions: List<String>,
    @SerializedName("etag") val etag: String?,
    @SerializedName("fetchedAt") val fetchedAtMillis: Long,
    /** Release date of the newest version, from its .pom Last-Modified. Immutable per version. */
    @SerializedName("newestReleaseAt") val newestReleaseAtMillis: Long?,
    @SerializedName("newestReleaseVersion") val newestReleaseVersion: String?,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * One small JSON file per group:artifact under a plugin-owned directory.
 * Writes are atomic (temp file + move); unreadable files are treated as absent
 * and deleted — the cache is always disposable, never a source of truth.
 *
 * Pure file logic with an injected directory so tests use a temp dir.
 */
class DiskVersionCache(private val directory: Path) {

    private val gson = Gson()

    fun read(coordinates: Coordinates): CachedArtifact? {
        val file = fileFor(coordinates)
        if (!Files.exists(file)) return null
        return try {
            val parsed = gson.fromJson(Files.readString(file), CachedArtifact::class.java)
            if (parsed?.schema == CachedArtifact.SCHEMA_VERSION && parsed.versions != null) parsed else discard(file)
        } catch (_: Exception) {
            discard(file)
        }
    }

    fun write(coordinates: Coordinates, entry: CachedArtifact) {
        try {
            Files.createDirectories(directory)
            val file = fileFor(coordinates)
            val temp = Files.createTempFile(directory, "staleguard", ".tmp")
            Files.writeString(temp, gson.toJson(entry))
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            // A failed cache write must never break the feature; next lookup refetches.
        }
    }

    private fun discard(file: Path): CachedArtifact? {
        runCatching { Files.deleteIfExists(file) }
        return null
    }

    /** Filesystem-safe, collision-free file name for the coordinates. */
    private fun fileFor(coordinates: Coordinates): Path {
        val safe = "${coordinates.groupId}_${coordinates.artifactId}"
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
        return directory.resolve("$safe.json")
    }
}
