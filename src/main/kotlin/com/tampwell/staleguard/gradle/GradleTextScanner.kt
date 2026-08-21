package com.tampwell.staleguard.gradle

/**
 * Text scan of Gradle build files for the tool window. Not PSI-based on
 * purpose: the panels live in the main plugin and must keep working when the
 * Groovy or Kotlin plugin is disabled. Slightly under-counting exotic
 * notations beats a ClassNotFoundException; the editor inspections remain the
 * source of truth.
 */
object GradleTextScanner {

    data class Scanned(
        val group: String,
        val name: String,
        val version: String,
        val offset: Int,
        /** Catalog accessor (`gson`, `kotlin.stdlib`) when this hit is a libs reference. */
        val catalogAccessor: String? = null,
        /** gradle.properties key the version came from — batch apply must skip these. */
        val propertyKey: String? = null,
    )

    private val NOTATION = Regex("""["']([A-Za-z0-9_.\-]+:[A-Za-z0-9_.\-]+:[A-Za-z0-9_.\-+]+)["']""")
    private val INTERPOLATED = Regex("""["']([A-Za-z0-9_.\-]+:[A-Za-z0-9_.\-]+):\$(?:\{([A-Za-z0-9_.]+)}|([A-Za-z0-9_.]+))["']""")
    private val LIBS_REF = Regex("""\blibs((?:\.[A-Za-z0-9_]+)+)\b""")

    fun scan(
        text: String,
        catalog: VersionCatalog.Parsed,
        /** Opt-in: resolve `"g:a:${'$'}{prop}"` hits from gradle.properties for the stats views. */
        gradleProperties: Map<String, String> = emptyMap(),
    ): List<Scanned> {
        val out = mutableListOf<Scanned>()
        for (match in NOTATION.findAll(text)) {
            val parsed = GradleNotationParser.parse(match.groupValues[1]) ?: continue
            out += Scanned(parsed.group, parsed.name, parsed.version, match.range.first)
        }
        if (gradleProperties.isNotEmpty()) {
            for (match in INTERPOLATED.findAll(text)) {
                val key = match.groupValues[2].ifEmpty { match.groupValues[3] }
                val version = gradleProperties[key] ?: continue
                val coordinate = match.groupValues[1].split(':')
                out += Scanned(coordinate[0], coordinate[1], version, match.range.first, propertyKey = key)
            }
        }
        for (match in LIBS_REF.findAll(text)) {
            val accessor = match.groupValues[1].removePrefix(".")
            // libs.versions.* / libs.plugins.* accessors are not library deps
            if (accessor.startsWith("versions") || accessor.startsWith("plugins")) continue
            val resolved = catalog.resolve(accessor) ?: continue
            out += Scanned(resolved.group, resolved.name, resolved.version, match.range.first, catalogAccessor = accessor)
        }
        return out
    }
}
