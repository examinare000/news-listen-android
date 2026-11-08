package com.rioikeda.newslisten.preferences.app

import com.rioikeda.newslisten.core.Difficulty
import com.rioikeda.newslisten.core.PlaybackSpeed
import com.rioikeda.newslisten.preferences.ArticleOpenMode
import com.rioikeda.newslisten.preferences.TimeFormat
import com.rioikeda.newslisten.preferences.domain.WeeklyGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 画面が読む [PreferencesView] の値の不変（android-check-06）。比較元は必ずリテラルで書く。
 * 生成に渡した集合を比較元にすると、書き換えと同じインスタンスを見比べて空振りの green になるため。
 */
class PreferencesViewEncapsulationTest {

    private fun view(seen: Set<String>) = PreferencesView(
        defaultDifficulty = Difficulty.TOEIC_600,
        defaultPlaybackSpeed = PlaybackSpeed.DEFAULT,
        weeklyGoal = WeeklyGoal.DEFAULT,
        articleOpenMode = ArticleOpenMode.DEFAULT,
        timeFormat = TimeFormat.DEFAULT,
        sfxEnabled = true,
        hapticsEnabled = true,
        seenAchievementIds = seen,
    )

    @Test
    fun `android-check-06 PreferencesViewのseenAchievementIdsは構築後に渡した集合を書き換えても変わらない`() {
        val source = mutableSetOf("streak_7", "completed_10")

        val view = view(source)
        source.clear()
        source.add("other")

        assertEquals(setOf("streak_7", "completed_10"), view.seenAchievementIds)
    }

    @Test
    fun `android-check-06 PreferencesViewのseenAchievementIdsは公開された参照から書き換えられない`() {
        val view = view(mutableSetOf("streak_7", "completed_10"))

        @Suppress("UNCHECKED_CAST")
        val exposed = view.seenAchievementIds as MutableSet<String>
        assertThrows(UnsupportedOperationException::class.java) { exposed.add("other") }
        assertThrows(UnsupportedOperationException::class.java) { exposed.remove("streak_7") }

        assertEquals(setOf("streak_7", "completed_10"), view.seenAchievementIds)
    }
}
