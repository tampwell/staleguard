package com.tampwell.staleguard.impact

import java.nio.file.Path
import java.util.zip.ZipFile

/** Reads a whole jar or class-file tree into its linkage view. Pure file work, no platform. */
object JarScanner {

    /**
     * A compile-output directory as one [LinkageAudit.JarScans]: the user's
     * own classes joining the audit, which is where a version conflict
     * actually bites. Returns null when [root] does not exist, because an
     * absent output directory proves nothing about the code that would have
     * been in it — the caller states that rather than claiming clean.
     */
    fun scanDirectory(root: java.nio.file.Path, label: String): LinkageAudit.JarScans? {
        if (!java.nio.file.Files.isDirectory(root)) return null
        val classes = mutableListOf<ClassScan>()
        return runCatching {
            java.nio.file.Files.walk(root).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".class") }.forEach { file ->
                    val data = java.nio.file.Files.readAllBytes(file)
                    runCatching { ClassFileApiReader.scan(data) }.getOrNull()?.let { classes += it }
                }
            }
            LinkageAudit.JarScans(label, classes)
        }.getOrNull()
    }

    /** Newest class-file timestamp under [root], for the "as of last build" line. Null when none exist. */
    fun newestClassMillis(root: java.nio.file.Path): Long? =
        runCatching {
            java.nio.file.Files.walk(root).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".class") }
                    .mapToLong { java.nio.file.Files.getLastModifiedTime(it).toMillis() }
                    .max().let { if (it.isPresent) it.asLong else null }
            }
        }.getOrNull()

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
