package com.tampwell.staleguard.gradle

import com.intellij.openapi.vfs.VirtualFile

/**
 * The pre-catalog idiom: an `object Versions { const val gson = "2.10.1" }`
 * in buildSrc, referenced as `"g:a:${'$'}{Versions.gson}"`. Resolved read-only —
 * freshness warnings fire, quick fixes stay off (editing buildSrc Kotlin from
 * a text range is a stronger promise than a v1 should make). Keys come back
 * as `Versions.<name>` so they merge into the same resolution map as
 * gradle.properties without colliding.
 */
object BuildSrcVersions {

    private val VERSIONS_OBJECT = Regex("""object\s+Versions\s*\{(.*?)^}""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE))
    private val CONST_VAL = Regex("const\\s+val\\s+([A-Za-z0-9_]+)\\s*=\\s*\"([^\"\$]+)\"")

    fun parse(text: String): Map<String, String> {
        val body = VERSIONS_OBJECT.find(text)?.groupValues?.get(1) ?: return emptyMap()
        return CONST_VAL.findAll(body).associate { "Versions.${it.groupValues[1]}" to it.groupValues[2] }
    }

    /** Kotlin sources under buildSrc/src/main/kotlin that declare an object Versions. */
    fun find(buildFile: VirtualFile?): Map<String, String> {
        var dir = buildFile?.parent
        var depth = 0
        while (dir != null && depth < 6) {
            val kotlinDir = dir.findChild("buildSrc")
                ?.findChild("src")?.findChild("main")?.findChild("kotlin")
            if (kotlinDir != null) {
                val values = mutableMapOf<String, String>()
                collectKt(kotlinDir, values, remaining = intArrayOf(25))
                return values
            }
            dir = dir.parent
            depth++
        }
        return emptyMap()
    }

    private fun collectKt(dir: VirtualFile, into: MutableMap<String, String>, remaining: IntArray) {
        for (child in dir.children) {
            if (remaining[0] <= 0) return
            if (child.isDirectory) {
                collectKt(child, into, remaining)
            } else if (child.name.endsWith(".kt")) {
                remaining[0]--
                val text = try {
                    String(child.contentsToByteArray())
                } catch (_: Exception) {
                    continue
                }
                if ("object Versions" in text) into += parse(text)
            }
        }
    }
}
