package com.tampwell.staleguard.impact

import java.io.InputStream
import java.nio.file.Path
import java.util.zip.ZipFile

/** Builds an [ApiSurface] from a jar on disk. Blocking; callers run it off the EDT. */
object JarApiReader {

    /**
     * Reads every class in [jar] and returns its public surface.
     *
     * META-INF is skipped wholesale, which also skips META-INF/versions:
     * multi-release jars are compared at their base version, the one every
     * supported JVM actually links against. Comparing a jar's Java 21 variant
     * against another jar's base would manufacture removals that no user could
     * ever hit.
     *
     * [cancelled] is polled per entry so a large jar stays interruptible.
     */
    fun read(jar: Path, cancelled: () -> Boolean = { false }): ApiSurface {
        val classes = mutableMapOf<String, ClassApi>()
        ZipFile(jar.toFile()).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                if (cancelled()) return ApiSurface.EMPTY
                val entry = entries.nextElement()
                val name = entry.name
                if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue
                val data = zip.getInputStream(entry).use { it.readAllBytesCompat(entry.size) }
                // One unreadable entry must not lose the other few thousand:
                // a shaded jar occasionally carries a deliberately broken class.
                runCatching { ClassFileApiReader.read(data) }
                    .getOrNull()
                    ?.let { classes[it.internalName] = it }
            }
        }
        return ApiSurface(classes)
    }

    /** InputStream.readAllBytes is Java 9+; the plugin targets a Java 17 baseline but sizes the buffer itself. */
    private fun InputStream.readAllBytesCompat(sizeHint: Long): ByteArray {
        val initial = if (sizeHint in 1..MAX_PRESIZE) sizeHint.toInt() else DEFAULT_BUFFER
        val out = java.io.ByteArrayOutputStream(initial)
        val buffer = ByteArray(DEFAULT_BUFFER)
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private const val DEFAULT_BUFFER = 8192
    private const val MAX_PRESIZE = 16L * 1024 * 1024
}
