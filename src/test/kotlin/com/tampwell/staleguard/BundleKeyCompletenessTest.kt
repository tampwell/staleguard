package com.tampwell.staleguard

import java.io.File
import java.util.Properties
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-checks every bundle key referenced from code and plugin descriptors
 * against StaleguardBundle.properties. A missing key is a runtime crash the
 * compiler can't see; this makes it a test failure instead.
 *
 * Keys assembled at runtime (severity.${'$'}{...}) can't be checked statically —
 * their PREFIXES are allowlisted below and their completeness is each call
 * site's own test's job.
 */
class BundleKeyCompletenessTest {

    private val messageCall = Regex("""StaleguardBundle\s*\.\s*message\(\s*"([^"$]+)"""")
    private val xmlKey = Regex("""(?:key|groupKey)="([^"]+)"""")
    private val anyStringLiteral = Regex(""""([^"$\n]+)"""")

    private val dynamicPrefixes = listOf("severity.")

    private fun bundleKeys(): Set<String> {
        val properties = Properties()
        File("src/main/resources/messages/StaleguardBundle.properties").inputStream().use(properties::load)
        return properties.stringPropertyNames()
    }

    private fun referencedKeys(): Map<String, MutableList<String>> {
        val refs = mutableMapOf<String, MutableList<String>>()
        File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            messageCall.findAll(file.readText()).forEach { match ->
                refs.getOrPut(match.groupValues[1]) { mutableListOf() } += file.name
            }
        }
        File("src/main/resources/META-INF").walkTopDown().filter { it.extension == "xml" }.forEach { file ->
            xmlKey.findAll(file.readText()).forEach { match ->
                refs.getOrPut(match.groupValues[1]) { mutableListOf() } += file.name
            }
        }
        return refs
    }

    @Test
    fun `every referenced key exists in the bundle`() {
        val known = bundleKeys()
        val missing = referencedKeys().filterKeys { it !in known }
        assertTrue(
            "Bundle keys referenced but not defined (runtime crash): " +
                missing.entries.joinToString { "${it.key} (${it.value.distinct().joinToString()})" },
            missing.isEmpty(),
        )
    }

    @Test
    fun `every bundle key is referenced or covered by a dynamic prefix`() {
        // Keys also count as referenced when they appear as a plain string
        // literal (enum constructor args, helper params) — message(variable)
        // call sites make the strict regex blind to them.
        val literals = mutableSetOf<String>()
        File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            anyStringLiteral.findAll(file.readText()).forEach { literals += it.groupValues[1] }
        }
        val referenced = referencedKeys().keys + literals
        val dead = bundleKeys().filterNot { key ->
            key in referenced || dynamicPrefixes.any { key.startsWith(it) }
        }
        assertTrue("Dead bundle keys (delete or wire up): $dead", dead.isEmpty())
    }

    @Test
    fun `sanity - the scan actually finds keys`() {
        val refs = referencedKeys()
        assertTrue("scan found suspiciously few keys: ${refs.size}", refs.size > 80)
        assertTrue("plugin.xml keys missing from scan", "inspection.freshness.display.name" in refs)
    }
}
