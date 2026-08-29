package com.tampwell.staleguard.impact

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The light fixture's module has no compiler output configured, which is
 * exactly the NothingBuilt world: these tests pin that an unbuilt project
 * flows through collection, standing, service, and dialog without inventing
 * a single claim about the user's code.
 */
class ModuleOutputsPlatformTest : BasePlatformTestCase() {

    fun `test an unbuilt project collects as nothing built`() {
        val outputs = ModuleOutputs.collect(project)

        assertEquals(OwnCodeAudit.Standing.NothingBuilt, OwnCodeAudit.standing(outputs))
        assertTrue(OwnCodeAudit.auditableScans(outputs).isEmpty())
    }

    fun `test the service audit carries the nothing-built standing`() {
        val result = ClasspathLinkageService.getInstance(project).audit(EmptyProgressIndicator())

        assertEquals(OwnCodeAudit.Standing.NothingBuilt, result.ownCode)
        assertTrue(result.report.clean)
    }

    fun `test own-code labels are visually distinct from jar names`() {
        assertEquals("your code (app)", ModuleOutputs.ownCodeLabel("app"))
    }
}
