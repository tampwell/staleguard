package com.tampwell.staleguard.gradle

import com.intellij.openapi.vfs.VirtualFile

/**
 * The pre-catalog idiom: an `object Versions { const val gson = "2.10.1" }`
 * in buildSrc, referenced as `"g:a:${'$'}{Versions.gson}"`. Keys come back
 * as `Versions.<name>` so they merge into the same resolution map as
 * gradle.properties without colliding. Since 2.7.0 the constants are also
 * WRITABLE through [valueRange]: the same regex that reads a value locates
 * the exact text span to replace, so scan and edit can never disagree.
 */
object BuildSrcVersions {

    private val VERSIONS_OBJECT = Regex("""object\s+Versions\s*\{(.*?)^}""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE))
    private val CONST_VAL = Regex("const\\s+val\\s+([A-Za-z0-9_]+)\\s*=\\s*\"([^\"\$]+)\"")

    fun parse(text: String): Map<String, String> {
        val body = VERSIONS_OBJECT.find(text)?.groupValues?.get(1) ?: return emptyMap()
        return CONST_VAL.findAll(body).associate { "Versions.${it.groupValues[1]}" to it.groupValues[2] }
    }

    /**
     * The edit for a buildSrc constant bump: the exact text range of the
     * quoted value of `const val <name>` inside the Versions object, in file
     * offsets. Pure and regex-identical to [parse], so what the scan read is
     * what the write replaces. [key] accepts `Versions.name` or bare `name`.
     */
    fun valueRange(text: String, key: String): IntRange? {
        val name = key.removePrefix("Versions.")
        val objectMatch = VERSIONS_OBJECT.find(text) ?: return null
        val bodyGroup = objectMatch.groups[1] ?: return null
        for (match in CONST_VAL.findAll(bodyGroup.value)) {
            if (match.groupValues[1] != name) continue
            val valueGroup = match.groups[2] ?: return null
            return (bodyGroup.range.first + valueGroup.range.first)..(bodyGroup.range.first + valueGroup.range.last)
        }
        return null
    }

    /**
     * The buildSrc source file declaring [key], for the write path. The
     * caller re-locates the range in the file's DOCUMENT text at apply time,
     * because an unsaved editor change must win over what disk says.
     */
    fun fileFor(buildFile: VirtualFile?, key: String): VirtualFile? {
        var dir = buildFile?.parent
        var depth = 0
        while (dir != null && depth < 6) {
            val kotlinDir = dir.findChild("buildSrc")
                ?.findChild("src")?.findChild("main")?.findChild("kotlin")
            if (kotlinDir != null) {
                val files = mutableListOf<VirtualFile>()
                collectKtFiles(kotlinDir, files, remaining = intArrayOf(25))
                return files.firstOrNull { file ->
                    val text = runCatching { String(file.contentsToByteArray()) }.getOrNull()
                    text != null && "object Versions" in text && valueRange(text, key) != null
                }
            }
            dir = dir.parent
            depth++
        }
        return null
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
