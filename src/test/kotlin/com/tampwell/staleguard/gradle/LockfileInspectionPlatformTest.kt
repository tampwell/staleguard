package com.tampwell.staleguard.gradle

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.tampwell.staleguard.settings.StaleguardSettings

/**
 * The inspection against real fixture files: a lockfile beside a build file,
 * highlighted the way the daemon would. Vulnerability checks are switched
 * off so nothing enqueues network lookups; drift is pure local logic.
 */
class LockfileInspectionPlatformTest : BasePlatformTestCase() {

    private var vulnerabilityChecksWereEnabled = true

    override fun setUp() {
        super.setUp()
        val state = StaleguardSettings.getInstance().state
        vulnerabilityChecksWereEnabled = state.vulnerabilityChecksEnabled
        state.vulnerabilityChecksEnabled = false
        myFixture.enableInspections(LockfileInspection())
    }

    override fun tearDown() {
        try {
            StaleguardSettings.getInstance().state.vulnerabilityChecksEnabled = vulnerabilityChecksWereEnabled
        } finally {
            super.tearDown()
        }
    }

    private fun driftWarnings() = myFixture.doHighlighting()
        .filter { it.description?.contains("--write-locks") == true }

    fun `test the drifted line warns and the in-step line stays silent`() {
        myFixture.addFileToProject(
            "build.gradle.kts",
            """
            dependencies {
                implementation("com.example:lib:1.1")
                implementation("com.example:other:2.0")
            }
            """.trimIndent(),
        )
        val lockfile = myFixture.addFileToProject(
            "gradle.lockfile",
            "com.example:lib:1.0=compileClasspath\ncom.example:other:2.0=compileClasspath\n",
        )
        myFixture.configureFromExistingVirtualFile(lockfile.virtualFile)

        val warnings = driftWarnings()

        assertEquals(1, warnings.size)
        val description = warnings.single().description
        assertTrue(description.contains("1.0"))
        assertTrue(description.contains("1.1"))
        // The warning sits on the drifted line, not anywhere else.
        val lineText = myFixture.file.text.substring(warnings.single().startOffset, warnings.single().endOffset)
        assertTrue(lineText.contains("com.example:lib:1.0"))
    }

    fun `test a dynamic declaration never drifts`() {
        myFixture.addFileToProject(
            "build.gradle.kts",
            """
            dependencies {
                implementation("com.example:lib:1.+")
            }
            """.trimIndent(),
        )
        val lockfile = myFixture.addFileToProject("gradle.lockfile", "com.example:lib:1.0=compileClasspath\n")
        myFixture.configureFromExistingVirtualFile(lockfile.virtualFile)

        assertEmpty(driftWarnings())
    }

    fun `test no sibling build file means no drift`() {
        val lockfile = myFixture.addFileToProject("gradle.lockfile", "com.example:lib:1.0=compileClasspath\n")
        myFixture.configureFromExistingVirtualFile(lockfile.virtualFile)

        assertEmpty(driftWarnings())
    }

    fun `test a file that merely ends in lockfile is left alone`() {
        myFixture.addFileToProject(
            "build.gradle.kts",
            """
            dependencies {
                implementation("com.example:lib:1.1")
            }
            """.trimIndent(),
        )
        val stray = myFixture.addFileToProject("notes.lockfile", "com.example:lib:1.0=compileClasspath\n")
        myFixture.configureFromExistingVirtualFile(stray.virtualFile)

        assertEmpty(driftWarnings())
    }
}
