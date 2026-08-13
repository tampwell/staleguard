package com.tampwell.staleguard.inspection

import com.intellij.codeInspection.ProblemHighlightType
import com.tampwell.staleguard.version.UpgradeSeverity
import org.junit.Assert.assertEquals
import org.junit.Test

class HighlightMappingTest {

    @Test
    fun `major maps to weak warning - risky upgrades nudge not nag`() {
        assertEquals(
            ProblemHighlightType.WEAK_WARNING,
            DependencyFreshnessInspection.highlightTypeFor(UpgradeSeverity.MAJOR),
        )
    }

    @Test
    fun `minor and patch map to standard warning`() {
        assertEquals(
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
            DependencyFreshnessInspection.highlightTypeFor(UpgradeSeverity.MINOR),
        )
        assertEquals(
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
            DependencyFreshnessInspection.highlightTypeFor(UpgradeSeverity.PATCH),
        )
    }

    @Test
    fun `qualifier maps to weak warning`() {
        assertEquals(
            ProblemHighlightType.WEAK_WARNING,
            DependencyFreshnessInspection.highlightTypeFor(UpgradeSeverity.QUALIFIER),
        )
    }

    @Test
    fun `every severity has a mapping`() {
        UpgradeSeverity.entries.forEach { DependencyFreshnessInspection.highlightTypeFor(it) }
    }
}
