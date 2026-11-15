package com.rioikeda.newslisten.learning.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** 月の活動日数の率は 31 日で割り 0〜1 に収める（android-dec-92）。 */
class MonthlyActivityTest {
    @Test
    fun `android-dec-92 活動日数を31日で割り0から1に収める`() {
        assertEquals(0.0, MonthlyActivity.rate(0), 0.0)
        assertEquals(8 / 31.0, MonthlyActivity.rate(8), 1e-12)
        assertEquals(1.0, MonthlyActivity.rate(31), 0.0)
        assertEquals(1.0, MonthlyActivity.rate(40), 0.0)
        assertEquals(0.0, MonthlyActivity.rate(-3), 0.0)
    }
}
