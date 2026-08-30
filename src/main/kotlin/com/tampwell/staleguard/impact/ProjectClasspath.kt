package com.tampwell.staleguard.impact

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VfsUtilCore
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * The jars the IDE has already resolved for this project.
 *
 * Using the IDE's library model rather than guessing at ~/.m2 and the Gradle
 * cache means the current version's binary is found the same way for Maven,
 * Gradle, and Android Studio, including composite builds and custom cache
 * locations — and it never needs downloading, because the project compiles
 * against it.
 */
object ProjectClasspath {

    fun libraryJars(project: Project): List<Path> = inReadAction {
        OrderEnumerator.orderEntries(project).jarPaths()
    }

    /**
     * The jar holding [artifactId] at [version], if this project already has
     * it. Matches the Maven-layout filename first, then the repository path
     * shape, which covers Gradle's cache (…/artifact/version/hash/name.jar)
     * and Android's extracted aars.
     */
    fun findArtifactJar(jars: List<Path>, artifactId: String, version: String): Path? {
        val exactName = "$artifactId-$version.jar"
        jars.firstOrNull { it.fileName.toString() == exactName }?.let { return it }
        val pathFragment = "/$artifactId/$version/"
        return jars.firstOrNull { it.toString().replace('\\', '/').contains(pathFragment) }
    }
}

/** Caller must already hold a read action. */
internal fun OrderEnumerator.jarPaths(): List<Path> =
    librariesOnly()
        .classes()
        .roots
        .mapNotNull { root -> VfsUtilCore.getVirtualFileForJar(root) ?: root.takeIf { !it.isDirectory } }
        .mapNotNull { file -> runCatching { Path.of(file.path) }.getOrNull() }
        .filter { it.fileName.toString().endsWith(".jar") }
        .distinct()

/**
 * Read action helper.
 *
 * Not ReadAction.compute: that overload is deprecated from the 261 line
 * onward, and Application.runReadAction(Computable) is non-deprecated across
 * every line this plugin supports, all the way back to the 243 floor.
 */
internal fun <T> inReadAction(body: () -> T): T =
    ApplicationManager.getApplication().runReadAction(Computable(body))

/**
 * Reads single classes out of a jar set on demand, so the diff can follow a
 * supertype into a sibling jar without paying to parse the whole classpath.
 * Entries are opened lazily and cached; close it when the analysis is done.
 */
class ClasspathClassLookup(private val jars: List<Path>) : ClassApiLookup, AutoCloseable {

    private val open = HashMap<Path, ZipFile?>()
    private val parsed = HashMap<String, ClassApi?>()

    // Not getOrPut: it re-runs the loader whenever the stored value is null,
    // so every unresolvable supertype would rescan the whole classpath on each
    // of the thousands of member lookups a diff performs.
    override fun find(internalName: String): ClassApi? {
        if (parsed.containsKey(internalName)) return parsed[internalName]
        val entryName = "$internalName.class"
        var found: ClassApi? = null
        for (jar in jars) {
            val zip = open.getOrPut(jar) { runCatching { ZipFile(jar.toFile()) }.getOrNull() } ?: continue
            val entry = zip.getEntry(entryName) ?: continue
            val data = runCatching { zip.getInputStream(entry).use { it.readBytes() } }.getOrNull() ?: continue
            found = runCatching { ClassFileApiReader.read(data) }.getOrNull()
            if (found != null) break
        }
        parsed[internalName] = found
        return found
    }

    override fun close() {
        open.values.forEach { zip -> runCatching { zip?.close() } }
        open.clear()
    }
}
