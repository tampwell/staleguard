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

    /**
     * Kotlin sources under buildSrc/src/main/kotlin that declare an object
     * Versions. Called from highlighting passes, so results are cached per
     * buildSrc directory and keyed by the summed modification stamps of the
     * files walked — an edit anywhere in buildSrc invalidates, everything
     * else is a handful of stat-level checks instead of 25 file reads.
     */
    fun find(buildFile: VirtualFile?): Map<String, String> {
        var dir = buildFile?.parent
        var depth = 0
        while (dir != null && depth < 6) {
            val kotlinDir = dir.findChild("buildSrc")
                ?.findChild("src")?.findChild("main")?.findChild("kotlin")
            if (kotlinDir != null) return cachedParse(kotlinDir)
            dir = dir.parent
            depth++
        }
        return emptyMap()
    }

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Map<String, String>>>()

    private fun cachedParse(kotlinDir: VirtualFile): Map<String, String> {
        val files = mutableListOf<VirtualFile>()
        collectKtFiles(kotlinDir, files, remaining = intArrayOf(25))
        val stamp = files.fold(files.size.toLong()) { acc, f -> acc * 31 + f.modificationStamp + f.timeStamp }
        cache[kotlinDir.path]?.takeIf { it.first == stamp }?.let { return it.second }

        val values = mutableMapOf<String, String>()
        for (file in files) {
            val text = try {
                String(file.contentsToByteArray())
            } catch (_: Exception) {
                continue
            }
            if ("object Versions" in text) values += parse(text)
        }
        cache[kotlinDir.path] = stamp to values
        return values
    }

    private fun collectKtFiles(dir: VirtualFile, into: MutableList<VirtualFile>, remaining: IntArray) {
        for (child in dir.children) {
            if (remaining[0] <= 0) return
            if (child.isDirectory) {
                collectKtFiles(child, into, remaining)
            } else if (child.name.endsWith(".kt")) {
                remaining[0]--
                into += child
            }
        }
    }
}
