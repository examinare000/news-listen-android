package com.rioikeda.newslisten.learning.domain

import com.rioikeda.newslisten.learning.ui.AchievementCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 実績の判定は domain の [Achievement]（android-rule-learning-06）。比較元はリテラルで書く。 */
class AchievementTest {
    private val expectedIds = listOf(
        "first_episode_completed",
        "first_quiz_correct",
        "streak_7",
        "streak_30",
        "streak_100",
        "completed_10",
        "completed_50",
    )

    @Test
    fun `android-rule-learning-06 実績のidは7件をこの順で持ち表示のカタログは同じ順に名前と説明を持つ`() {
        assertEquals(expectedIds, Achievement.ids)
        assertEquals(expectedIds, AchievementCatalog.map { it.id })
        assertTrue(AchievementCatalog.all { it.name.isNotBlank() && it.description.isNotBlank() })
    }

    @Test
    fun `android-rule-learning-06 新しく解錠された実績は解錠済みの順を保ち既読に無いものだけを返す`() {
        val result = Achievement.newlyUnlocked(
            unlockedIds = listOf("completed_10", "streak_7", "unknown_id", "first_quiz_correct"),
            seenIds = setOf("streak_7"),
        )

        // カタログに無い id も残す（今の filterNot と同じ）
        assertEquals(listOf("completed_10", "unknown_id", "first_quiz_correct"), result)
    }

    @Test
    fun `android-rule-learning-06 既読が空なら解錠済みの全件を返し全部既読なら空を返す`() {
        val unlocked = listOf("streak_7", "completed_10")

        assertEquals(unlocked, Achievement.newlyUnlocked(unlocked, emptySet()))
        assertEquals(emptyList<String>(), Achievement.newlyUnlocked(unlocked, setOf("streak_7", "completed_10")))
    }
}
