package com.tampwell.staleguard.gradle

/**
 * Textual dependency scan of Gradle build files for the tool window.
 *
 * Deliberately NOT PSI-based: the statistics/timeline panels live in the main
 * plugin and must work even when the Groovy or Kotlin plugins are disabled,
 * so they cannot touch those plugins' PSI classes. A text scan finds the same
 * declarations our inspections support — string notation and version-catalog
 * references — with character offsets for click-to-navigate. Interpolated
 * versions and named-argument notation are out of scope here (the editors'
 * inspections remain the source of truth); the panel slightly under-counting
 * exotic declarations beats a ClassNotFoundException.
 */
object GradleTextScanner {

    data class Scanned(val group: String, val name: String, val version: String, val offset: Int)

    /** `"group:artifact:version"` in single or double quotes; `${...}` never matches. */
    private val NOTATION =
        Regex("""["']([A-Za-z0-9_.\-]+:[A-Za-z0-9_.\-]+:[A-Za-z0-9_.\-+]+)["']""")

    /** `libs.some.accessor` chains (version-catalog references). */
    private val LIBS_REF = Regex("""\blibs((?:\.[A-Za-z0-9_]+)+)\b""")

    fun scan(text: String, catalog: VersionCatalog.Parsed): List<Scanned> {
        val out = mutableListOf<Scanned>()

        for (match in NOTATION.findAll(text)) {
            val parsed = GradleNotationParser.parse(match.groupValues[1]) ?: continue
            out += Scanned(parsed.group, parsed.name, parsed.version, match.range.first)
        }

        for (match in LIBS_REF.findAll(text)) {
            val accessor = match.groupValues[1].removePrefix(".")
            // libs.versions.* are version constants, libs.plugins.* are plugin
            // accessors — neither is a library dependency.
            if (accessor.startsWith("versions") || accessor.startsWith("plugins")) continue
            val resolved = catalog.resolve(accessor) ?: continue
            out += Scanned(resolved.group, resolved.name, resolved.version, match.range.first)
        }

        return out
    }
}
