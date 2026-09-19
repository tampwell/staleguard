package com.tampwell.staleguard.reach

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The walk at real scale: this plugin's own compiled classes as "the
 * project", against real platform and library jars. Guards the two failure
 * modes a class-hierarchy walk is known for: running too long, and fanning
 * out until everything is reached and the answer means nothing.
 */
class ReachabilityCorpusTest {

    private fun jarOf(className: String): Path? = runCatching {
        val url = javaClass.classLoader.getResource(className.replace('.', '/') + ".class") ?: return null
        if (url.protocol != "jar") return null
        Path.of(java.net.URI(url.path.substringBefore("!/")))
    }.getOrNull()

    @Test
    fun `walks a real classpath quickly without reaching everything`() {
        // Main classes load from the sandboxed plugin jar in tests; the compile
        // output directory is the plain class tree. Tests run in the project root.
        val ownClasses = Path.of("build/classes/kotlin/main").toAbsolutePath()
        assertTrue("compile output missing at $ownClasses", Files.isDirectory(ownClasses))

        val jars = listOf(
            "com.intellij.openapi.util.text.StringUtil",
            "com.intellij.psi.PsiElement",
            "com.intellij.openapi.editor.Editor",
            "kotlin.Unit",
            "kotlinx.coroutines.CoroutineScope",
            "com.google.common.collect.ImmutableList",
            "com.google.gson.Gson",
            "it.unimi.dsi.fastutil.ints.IntArrayList",
        ).mapNotNull(::jarOf).distinct()

        val entries = Files.walk(ownClasses).use { stream ->
            stream.filter { it.toString().endsWith(".class") }.toList()
                .map { ownClasses.relativize(it).joinToString("/").removeSuffix(".class") }
        }

        ClasspathClassSource.open(listOf(ownClasses) + jars).use { source ->
            // Live set at the walk's high-water mark: collect while everything
            // it built is still referenced. Peak "used" would also count garbage
            // a lazy collector simply had not bothered to reclaim yet.
            val runtime = Runtime.getRuntime()
            System.gc()
            val baseline = runtime.totalMemory() - runtime.freeMemory()
            var liveAtPeak = 0L
            val started = System.nanoTime()
            val result = Reachability.analyze(
                source, entries, PlatformClasses::bytes, Reachability.DEFAULT_MAX_METHODS, checkCanceled = {},
                atPeak = {
                    System.gc()
                    liveAtPeak = runtime.totalMemory() - runtime.freeMemory() - baseline
                },
            )
            val millis = (System.nanoTime() - started) / 1_000_000
            val usedMb = liveAtPeak / (1024 * 1024)

            // Count every method on the classpath once, for the fan-out ratio.
            var totalMethods = 0
            for (name in source.classNames) {
                val bytes = source.bytes(name) ?: continue
                totalMethods += runCatching { ClassBodyReader.read(bytes, scanBodies = false).methods.size }.getOrDefault(0)
            }
            val ratio = result.reachedMethods.toDouble() / totalMethods
            println(
                "CORPUS ${jars.size} jars + ${entries.size} own classes, ${source.classNames.size} classes, " +
                    "$totalMethods methods; reached ${result.reachedMethods} (${"%.1f".format(ratio * 100)}%) " +
                    "in ${millis}ms (walk ${result.millis}ms), ~${usedMb}MB, complete=${result.complete} ${result.gaps}",
            )
            result.topFanout.forEach { (site, count) -> println("FANOUT $count ${site.owner}.${site.name}${site.descriptor}") }
            assertTrue("walk took ${millis}ms", millis < 60_000)
            assertTrue(result.complete)
            // This "project" is a plugin wired into most of one framework whose
            // thousands of extension classes are created reflectively, so a
            // sound walk rightly reaches much of it (about 60%). Precision is
            // proven where it matters, on a vulnerable library with a negative
            // control (ReachabilityRealWorldTest); this guards against runaway.
            assertTrue("reached ${"%.1f".format(ratio * 100)}% of the classpath: runaway dispatch", ratio < 0.8)
        }
    }
}
