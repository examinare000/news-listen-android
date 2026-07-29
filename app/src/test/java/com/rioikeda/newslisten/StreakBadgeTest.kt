package com.rioikeda.newslisten

import com.rioikeda.newslisten.model.ListeningStreakResponse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreakBadgeTest {
    @Test
    fun `聴取日があり1日以上の時だけバッジを表示する`() {
        assertFalse(shouldShowStreakBadge(null))
        assertFalse(shouldShowStreakBadge(ListeningStreakResponse(0, false, "2026-07-28")))
        assertFalse(shouldShowStreakBadge(ListeningStreakResponse(3, false, null)))
        assertTrue(shouldShowStreakBadge(ListeningStreakResponse(3, true, "2026-07-29")))
    }
}
