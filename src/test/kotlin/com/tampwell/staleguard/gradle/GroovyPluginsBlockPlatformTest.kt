package com.tampwell.staleguard.gradle

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.literals.GrLiteral

/**
 * The text-anchored Groovy plugins-block path: matches come from the shared
 * scanner regexes, and this pins the part regexes can't cover — that
 * findElementAt on the version group's offset really lands inside the
 * version GrLiteral of real Groovy PSI.
 */
class GroovyPluginsBlockPlatformTest : BasePlatformTestCase() {

    fun `test version offsets anchor to their groovy literals`() {
        // The light fixture doesn't register Groovy's file type; the PSI
        // factory builds a real GroovyFile directly — same approach as the
        // kts tests with KtPsiFactory.
        val file: GroovyFile = org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElementFactory
            .getInstance(project)
            .createGroovyFile(
                """
                plugins {
                    id 'org.flywaydb.flyway' version '10.0.0'
                    id("com.diffplug.spotless") version "6.25.0"
                }
                """.trimIndent(),
                false,
                null,
            )
        val text = file.text
        val matches = GradleTextScanner.PLUGIN_ID.findAll(text).toList()
        assertEquals(2, matches.size)
        for (match in matches) {
            val versionRange = match.groups[2]!!.range
            val element = file.findElementAt(versionRange.first)
            val literal = com.intellij.psi.util.PsiTreeUtil
                .getParentOfType(element, GrLiteral::class.java, false)
            assertNotNull("no literal at ${'$'}{versionRange.first}", literal)
            assertEquals(match.groupValues[2], literal!!.value)
        }
    }
}
