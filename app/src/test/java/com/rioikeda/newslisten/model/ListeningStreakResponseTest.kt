package com.rioikeda.newslisten.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ListeningStreakResponse の検証。
 *
 * 正本: backend/api/schemas.py:149-159（ListeningStreakResponse、issue #165）。
 * current_streak_days == 0 は「聴取歴なし」を意味しない（last_listened_day が
 * non-null のまま 0 になり得る）ため、last_listened_day が null になるのは
 * 聴取記録が一度もない場合のみ（iOS ListeningStreak.swift のコメントと同じ注意）。
 */
class ListeningStreakResponseTest {

    @Test
    fun 聴取記録ありをデコードできる() {
        val json = """
            {"current_streak_days": 3, "today_listened": true, "last_listened_day": "2026-07-08"}
        """.trimIndent()

        val streak = NewsListenJson.decodeFromString(ListeningStreakResponse.serializer(), json)

        assertEquals(3, streak.currentStreakDays)
        assertTrue(streak.todayListened)
        assertEquals("2026-07-08", streak.lastListenedDay)
    }

    @Test
    fun 聴取記録が一度もない場合はlast_listened_dayがnull() {
        val json = """
            {"current_streak_days": 0, "today_listened": false, "last_listened_day": null}
        """.trimIndent()

        val streak = NewsListenJson.decodeFromString(ListeningStreakResponse.serializer(), json)

        assertEquals(0, streak.currentStreakDays)
        assertFalse(streak.todayListened)
        assertNull(streak.lastListenedDay)
    }
}
