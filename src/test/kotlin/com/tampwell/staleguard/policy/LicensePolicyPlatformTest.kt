package com.tampwell.staleguard.policy

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.tampwell.staleguard.inspection.LicenseProblems
import java.io.File

/**
 * End-to-end policy wiring against a real project root: the committed
 * .staleguard.toml is read through the VFS exactly like production, so this
 * covers what a screenshot of the editor would — parse, cache keying by
 * modification stamp, and the deny/warn/clean verdicts LicenseProblems
 * renders — without needing an interactive IDE.
 */
class LicensePolicyPlatformTest : BasePlatformTestCase() {

    private val gplString = "The GNU General Public License, v2 with FOSS exception"
    private val eplString = "Eclipse Public License v2.0"
    private val apacheString = "Apache License, Version 2.0"

    override fun tearDown() {
        try {
            policyFile().delete()
            refreshBase()
        } finally {
            super.tearDown()
        }
    }

    private fun policyFile(): File = File(project.basePath!!, ".staleguard.toml")

    private fun refreshBase() {
        File(project.basePath!!).mkdirs()
        LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath!!)
            ?.refresh(false, true)
    }

    private fun writePolicy(content: String) {
        File(project.basePath!!).mkdirs()
        policyFile().writeText(content)
        refreshBase()
    }

    fun testNoPolicyFileMeansSilence() {
        assertTrue(ProjectPolicyService.getInstance(project).licensePolicy().isEmpty)
        assertNull(LicenseProblems.check(project, "mysql:mysql-connector-java", listOf(gplString)))
    }

    fun testCommittedRulesProduceDenyAndWarnFindings() {
        writePolicy(
            """
            [ignore]
            dependencies = ["com.example.internal:*"]

            [licenses]
            deny = ["*GNU General Public License*"]
            warn = ["*Eclipse Public License*"]
            """.trimIndent(),
        )

        val denied = LicenseProblems.check(project, "mysql:mysql-connector-java", listOf(gplString))
        assertNotNull(denied)
        assertEquals(ProblemHighlightType.GENERIC_ERROR_OR_WARNING, denied!!.highlight)
        assertTrue(denied.message.contains(gplString))
        assertTrue(denied.message.contains("denied"))

        val warned = LicenseProblems.check(project, "org.junit.jupiter:junit-jupiter", listOf(eplString))
        assertNotNull(warned)
        assertEquals(ProblemHighlightType.WEAK_WARNING, warned!!.highlight)
        assertTrue(warned.message.contains(eplString))

        assertNull(LicenseProblems.check(project, "com.google.code.gson:gson", listOf(apacheString)))

        // The same committed file's ignore table flows through the shared service.
        assertTrue(ProjectPolicyService.getInstance(project).isIgnored("com.example.internal", "team-commons"))
        assertFalse(ProjectPolicyService.getInstance(project).isIgnored("com.google.code.gson", "gson"))
    }

    fun testPolicyReloadsWhenTheFileChanges() {
        writePolicy("[licenses]\ndeny = [\"*GNU General Public License*\"]")
        assertNotNull(LicenseProblems.check(project, "mysql:mysql-connector-java", listOf(gplString)))

        writePolicy("[ignore]\ndependencies = []")
        assertNull(LicenseProblems.check(project, "mysql:mysql-connector-java", listOf(gplString)))
    }
}
