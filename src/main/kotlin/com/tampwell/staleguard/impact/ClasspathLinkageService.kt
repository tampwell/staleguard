package com.tampwell.staleguard.impact

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Runs the [LinkageAudit] over this project's resolved classpath.
 *
 * JDK membership questions go to the project SDK through PSI, because the
 * audit is meaningless without real JDK resolution (every hierarchy reaches
 * Object) and the SDK index answers for exactly the Java version this project
 * compiles against — reflection against the IDE's own runtime would answer
 * for the wrong JDK.
 */
@Service(Service.Level.PROJECT)
class ClasspathLinkageService(private val project: Project) {

    /** Jar scans keyed by path and modification time, so repeat runs re-read nothing. */
    private val scanCache = ConcurrentHashMap<Path, Pair<FileTime, LinkageAudit.JarScans>>()

    fun audit(indicator: ProgressIndicator): LinkageAudit.Report {
        val jars = ProjectClasspath.libraryJars(project)
        indicator.isIndeterminate = false

        val scans = jars.mapIndexedNotNull { index, jar ->
            indicator.checkCanceled()
            indicator.fraction = index.toDouble() / jars.size * SCAN_SHARE
            indicator.text2 = jar.fileName.toString()
            scansOf(jar)
        }

        indicator.text2 = ""
        indicator.fraction = SCAN_SHARE
        val platformMembers = PsiPlatformMembers(project)
        return LinkageAudit.run(scans) { internalName, memberName ->
            indicator.checkCanceled()
            platformMembers.has(internalName, memberName)
        }
    }

    private fun scansOf(jar: Path): LinkageAudit.JarScans? {
        val modified = runCatching { Files.getLastModifiedTime(jar) }.getOrNull() ?: return null
        scanCache[jar]?.takeIf { it.first == modified }?.let { return it.second }

        val classes = mutableListOf<ClassScan>()
        runCatching {
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
        }.getOrElse { return null }
        val scans = LinkageAudit.JarScans(jar.fileName.toString(), classes)
        scanCache[jar] = modified to scans
        return scans
    }

    companion object {
        fun getInstance(project: Project): ClasspathLinkageService = project.service()

        /** Share of the progress bar spent scanning jars; the rest is resolution. */
        private const val SCAN_SHARE = 0.7
    }
}
