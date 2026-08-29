package com.tampwell.staleguard.impact

/**
 * The rules for including the project's OWN compiled classes in a linkage
 * audit without lying about them.
 *
 * Compiled output is stale the moment source changes and absent before the
 * first build, and a verdict drawn from classes that no longer match the
 * source is worse than no verdict. Pure decisions, so the honesty rules are
 * tested directly.
 */
object OwnCodeAudit {

    /** One module's compile output as the audit sees it. */
    data class ModuleOutput(
        val moduleName: String,
        /** Null when the output directory does not exist or holds no classes. */
        val scans: LinkageAudit.JarScans?,
        /** Newest class-file timestamp, for the "as of last build" line. */
        val newestClassMillis: Long?,
    )

    sealed interface Standing {
        /** No module has any compiled output: say so, audit jars only, claim nothing about own code. */
        data object NothingBuilt : Standing

        /**
         * Some modules have output and some do not. The built ones join the
         * audit, but a CLEAN claim is forbidden: the missing modules' calls
         * were never checked, and "clean" would read as covering them.
         */
        data class PartiallyBuilt(val missingModules: List<String>, val asOfMillis: Long) : Standing

        /** Every module has output; findings and CLEAN both stand, dated by [asOfMillis]. */
        data class Built(val asOfMillis: Long) : Standing
    }

    fun standing(outputs: List<ModuleOutput>): Standing {
        val built = outputs.filter { it.scans != null && it.scans.classes.isNotEmpty() }
        if (built.isEmpty()) return Standing.NothingBuilt
        val asOf = built.mapNotNull { it.newestClassMillis }.maxOrNull() ?: 0L
        val missing = outputs.filter { it.scans == null || it.scans.classes.isEmpty() }.map { it.moduleName }
        return if (missing.isEmpty()) Standing.Built(asOf) else Standing.PartiallyBuilt(missing, asOf)
    }

    /**
     * Whether a clean own-code verdict may be stated for this standing.
     * Findings may always be shown — a real break found in stale output is
     * still worth investigating — but "your code is clean" is a promise, and
     * promises need the whole project built.
     */
    fun mayClaimClean(standing: Standing): Boolean = standing is Standing.Built

    /** The scans that actually join the audit, whatever the standing. */
    fun auditableScans(outputs: List<ModuleOutput>): List<LinkageAudit.JarScans> =
        outputs.mapNotNull { it.scans }.filter { it.classes.isNotEmpty() }
}
