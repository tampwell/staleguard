package com.tampwell.staleguard.impact

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.tampwell.staleguard.repository.Coordinates
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The binary diff for one version pair, persisted so a second look costs no
 * download.
 *
 * Only the removed members are kept, never the jars: for a big upgrade that is
 * a few hundred short strings instead of two multi-megabyte binaries. The
 * answer is immutable — released versions do not change — so there is no TTL,
 * only a schema version.
 */
class RemovedMembersCache(private val directory: Path) {

    private val gson = Gson()

    fun read(coordinates: Coordinates, from: String, to: String): Set<MemberRef>? {
        val file = fileFor(coordinates, from, to)
        if (!Files.exists(file)) return null
        return try {
            val parsed = gson.fromJson(Files.readString(file), Entry::class.java)
            if (parsed?.schema != SCHEMA_VERSION || parsed.removed == null) discard(file) else parsed.decode()
        } catch (_: Exception) {
            discard(file)
        }
    }

    fun write(coordinates: Coordinates, from: String, to: String, removed: Set<MemberRef>) {
        try {
            Files.createDirectories(directory)
            val temp = Files.createTempFile(directory, "staleguard", ".tmp")
            Files.writeString(temp, gson.toJson(Entry.of(removed)))
            Files.move(
                temp,
                fileFor(coordinates, from, to),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            // A failed cache write must never break the feature; the next run recomputes.
        }
    }

    private fun discard(file: Path): Set<MemberRef>? {
        runCatching { Files.deleteIfExists(file) }
        return null
    }

    private fun fileFor(coordinates: Coordinates, from: String, to: String): Path =
        directory.resolve(sanitize("${coordinates.groupId}__${coordinates.artifactId}__${from}__$to") + ".json")

    private data class Entry(
        @SerializedName("schema") val schema: Int = SCHEMA_VERSION,
        /** "owner name descriptor", space-separated — none of the three can contain a space. */
        @SerializedName("removed") val removed: List<String>?,
    ) {
        fun decode(): Set<MemberRef> = removed.orEmpty().mapNotNullTo(LinkedHashSet()) { line ->
            val parts = line.split(' ')
            if (parts.size == 3) MemberRef(parts[0], parts[1], parts[2]) else null
        }

        companion object {
            fun of(removed: Set<MemberRef>) =
                Entry(removed = removed.map { "${it.owner} ${it.name} ${it.descriptor}" })
        }
    }

    private companion object {
        const val SCHEMA_VERSION = 1

        /** Coordinates and versions reach the filesystem, so anything path-significant is neutralised. */
        fun sanitize(key: String): String = key.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }
            .joinToString("")
            .take(180)
    }
}
