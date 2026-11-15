package com.rioikeda.newslisten

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.learning.app.FakeLearningApi
import com.rioikeda.newslisten.learning.app.StreakQuery
import com.rioikeda.newslisten.learning.app.StreakRecord
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreakBadgeTest {
    private suspend fun kotlinx.coroutines.test.TestScope.visibleAfterRefresh(result: () -> StreakRecord): Boolean {
        val api = FakeLearningApi().apply { onFetchStreak = { result() } }
        val query = StreakQuery(api, StandardTestDispatcher(testScheduler))
        query.refresh()
        return query.streak.value?.visible == true
    }

    @Test
    fun `聴取日があり1日以上の時だけバッジを表示する`() = runTest {
        assertFalse(visibleAfterRefresh { throw ApiException.NotFound() })
        assertFalse(visibleAfterRefresh { StreakRecord(0, false, "2026-07-28") })
        assertFalse(visibleAfterRefresh { StreakRecord(3, false, null) })
        assertTrue(visibleAfterRefresh { StreakRecord(3, true, "2026-07-29") })
    }
}
