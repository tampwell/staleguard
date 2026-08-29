package com.tampwell.staleguard.impact

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap

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

    data class Result(val report: LinkageAudit.Report, val ownCode: OwnCodeAudit.Standing)

    fun audit(indicator: ProgressIndicator): Result {
        val jars = ProjectClasspath.libraryJars(project)
        indicator.isIndeterminate = false

        val scans = jars.mapIndexedNotNull { index, jar ->
            indicator.checkCanceled()
            indicator.fraction = index.toDouble() / jars.size * SCAN_SHARE
            indicator.text2 = jar.fileName.toString()
            scansOf(jar)
        }.toMutableList()

        // The user's own compiled classes join as one more scan set. Their
        // calls into the classpath are where a conflict actually bites, and
        // OwnCodeAudit.standing is what keeps a stale or partial build from
        // turning into a false promise.
        val outputs = ModuleOutputs.collect(project)
        val standing = OwnCodeAudit.standing(outputs)
        scans += OwnCodeAudit.auditableScans(outputs)

        indicator.text2 = ""
        indicator.fraction = SCAN_SHARE
        val platformMembers = PsiPlatformMembers(project)
        val report = LinkageAudit.run(scans) { internalName, memberName ->
            indicator.checkCanceled()
            platformMembers.has(internalName, memberName)
        }
        return Result(report, standing)
    }

    private fun scansOf(jar: Path): LinkageAudit.JarScans? {
        val modified = runCatching { Files.getLastModifiedTime(jar) }.getOrNull() ?: return null
        scanCache[jar]?.takeIf { it.first == modified }?.let { return it.second }
        val scans = JarScanner.scan(jar) ?: return null
        scanCache[jar] = modified to scans
        return scans
    }

    companion object {
        fun getInstance(project: Project): ClasspathLinkageService = project.service()

        /** Share of the progress bar spent scanning jars; the rest is resolution. */
        private const val SCAN_SHARE = 0.7
    }
}
