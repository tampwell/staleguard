package com.tampwell.staleguard.plan

import com.tampwell.staleguard.version.UpgradeSeverity
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class RecommendationTest {

    private val fresh = TimeUnit.DAYS.toMillis(30)

    @Test
    fun `vulnerable overrides everything, including a scary major`() {
        assertEquals(
            Recommendation.URGENT,
            Recommendation.of(UpgradeSeverity.MAJOR, fresh, abandoned = false, vulnerable = true),
        )
        assertEquals(
            Recommendation.URGENT,
            Recommendation.of(UpgradeSeverity.PATCH, null, abandoned = true, vulnerable = true),
        )
    }

    @Test
    fun `without a vulnerability the existing ladder is untouched`() {
        assertEquals(
            Recommendation.SAFE,
            Recommendation.of(UpgradeSeverity.PATCH, fresh, abandoned = false),
        )
        assertEquals(
            Recommendation.BREAKING,
            Recommendation.of(UpgradeSeverity.MAJOR, fresh, abandoned = false),
        )
        assertEquals(
            Recommendation.STALE,
            Recommendation.of(UpgradeSeverity.PATCH, fresh, abandoned = true),
        )
    }
}
