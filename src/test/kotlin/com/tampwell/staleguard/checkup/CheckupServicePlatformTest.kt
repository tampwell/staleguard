package com.tampwell.staleguard.checkup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.tampwell.staleguard.settings.StaleguardSettings

/**
 * The whole orchestration through the platform: read actions in smart mode,
 * the blocking lookup bridge, the linkage audit, the reachability check,
 * and the report. Offline mode keeps it off the network; the light fixture
 * is an unbuilt project with no build files, the case every new user with
 * an empty or unusual project meets first.
 */
class CheckupServicePlatformTest : BasePlatformTestCase() {

    private var wasOffline = false

    override fun setUp() {
        super.setUp()
        val state = StaleguardSettings.getInstance().state
        wasOffline = state.offlineMode
        state.offlineMode = true
    }

    override fun tearDown() {
        try {
            StaleguardSettings.getInstance().state.offlineMode = wasOffline
        } finally {
            super.tearDown()
        }
    }

    fun `test an empty unbuilt project runs end to end and says what it could not check`() {
        val future = ApplicationManager.getApplication().executeOnPooledThread<CheckupService.Outcome> {
            CheckupService.getInstance(project).run(EmptyProgressIndicator())
        }
        val outcome = PlatformTestUtil.waitForFuture(future, 60_000)

        assertEquals(100, outcome.report.score)
        assertEquals("A", outcome.report.grade)
        assertFalse(outcome.report.inputs.ownCodeChecked)
        assertTrue(CheckupReport.markdown(outcome.report, project.name).contains("was not built"))
    }
}
