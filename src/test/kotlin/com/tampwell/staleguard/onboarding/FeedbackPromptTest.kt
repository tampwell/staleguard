package com.tampwell.staleguard.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedbackPromptTest {

    @Test
    fun `asks exactly once, at the third moment of real value, and never again`() {
        assertEquals(listOf(false, false, true, false, false), (1..5).map { FeedbackPrompt.shouldAsk(it, asked = false) })
        assertEquals(false, FeedbackPrompt.shouldAsk(3, asked = true))
    }

    @Test
    fun `links point at the real pages`() {
        assertEquals("https://plugins.jetbrains.com/plugin/33571-staleguard/reviews", FeedbackPrompt.REVIEWS_URL)
        assertEquals("https://github.com/tampwell/staleguard/issues", FeedbackPrompt.ISSUES_URL)
    }
}
