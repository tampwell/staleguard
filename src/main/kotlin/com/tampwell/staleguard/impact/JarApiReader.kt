package com.tampwell.staleguard.impact

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
     * [cancelled] is polled per entry so a large jar stays interruptible, and
     * returns NULL rather than a partial surface when it fires. An empty
     * surface would flow through the diff as "this version removes nothing",
     * which is the one wrong answer that matters: a cancelled check must never
     * be able to report an all-clear.
     */
    fun read(jar: Path, cancelled: () -> Boolean = { false }): ApiSurface? {
        val classes = mutableMapOf<String, ClassApi>()
        ZipFile(jar.toFile()).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                if (cancelled()) return null
                val entry = entries.nextElement()
                val name = entry.name
                if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue
                val data = zip.getInputStream(entry).use { it.readBytes() }
                // One unreadable entry must not lose the other few thousand:
                // a shaded jar occasionally carries a deliberately broken class.
                runCatching { ClassFileApiReader.read(data) }
                    .getOrNull()
                    ?.let { classes[it.internalName] = it }
            }
        }
        return ApiSurface(classes)
    }
}
