package com.tampwell.staleguard.impact

import java.nio.file.Path
import java.util.zip.ZipFile

/** Reads a whole jar into its linkage view. Pure file work, no platform. */
object JarScanner {

    /**
     * Every class in [jar] as a [ClassScan], or null when the jar itself is
     * unreadable. META-INF is skipped wholesale, which also skips
     * META-INF/versions: like the API diff, linkage is judged at the base
     * version, the one every supported JVM actually links against.
     */
    fun scan(jar: Path): LinkageAudit.JarScans? {
        val classes = mutableListOf<ClassScan>()
        return runCatching {
            ZipFile(jar.toFile()).use { zip ->
                for (entry in zip.entries()) {
                    val name = entry.name
                    if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue
                    val data = zip.getInputStream(entry).use { it.readBytes() }
                    // One unreadable entry must not lose the jar; a shaded jar
                    // occasionally carries a deliberately broken class.
                    runCatching { ClassFileApiReader.scan(data) }.getOrNull()?.let { classes += it }
                }
            }
            LinkageAudit.JarScans(jar.fileName.toString(), classes)
        }.getOrNull()
    }
}
