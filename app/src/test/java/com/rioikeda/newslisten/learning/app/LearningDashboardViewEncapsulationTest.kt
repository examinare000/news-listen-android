package com.rioikeda.newslisten.learning.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 画面が読む [LearningDashboardView] の値の不変（android-check-06）。比較元は必ずリテラルで書く。
 * 生成に渡した list を比較元にすると、書き換えと同じインスタンスを見比べて空振りの green になるため。
 */
class LearningDashboardViewEncapsulationTest {

    private fun view(
        unlocked: MutableList<UnlockedAchievement> = mutableListOf(UnlockedAchievement("streak_7", "2026-07-29")),
        newly: MutableList<UnlockedAchievement> = mutableListOf(UnlockedAchievement("streak_7", "2026-07-29")),
        vocabulary: MutableList<VocabularyRow> = mutableListOf(VocabularyRow("term", "意味", "2026-07-01")),
        monthly: MutableList<MonthlyActivityRow> = mutableListOf(MonthlyActivityRow("2026-07", 8, 0.25)),
        trend: MutableList<QuizTrendRow> = mutableListOf(QuizTrendRow("2026-07-20", 0.5)),
        history: MutableList<WeeklyRecordRow> = mutableListOf(WeeklyRecordRow("2026-W30", 5, 4, 0.8)),
    ) = LearningDashboardView(
        streakDays = 4,
        totalEpisodes = 12,
        vocabularyAcquired = 31,
        quizzedEpisodes = 2,
        averageCorrectRate = 0.75,
        currentDifficulty = "toeic_900",
        weeklyGoal = WeeklyGoalProgress(goalEpisodes = 5, completedThisWeek = 3, history = history),
        monthlyActivity = monthly,
        recentQuizTrend = trend,
        unlockedAchievements = unlocked,
        newlyUnlockedAchievements = newly,
        vocabularyCount = 1,
        recentVocabulary = vocabulary,
        hasVocabularyTest = false,
        readAs = ReadSubject("u-1"),
    )

    @Test
    fun `android-check-06 構築後に渡したlistを書き換えても6つの集合は変わらない`() {
        val unlocked = mutableListOf(UnlockedAchievement("streak_7", "2026-07-29"))
        val newly = mutableListOf(UnlockedAchievement("streak_7", "2026-07-29"))
        val vocabulary = mutableListOf(VocabularyRow("term", "意味", "2026-07-01"))
        val monthly = mutableListOf(MonthlyActivityRow("2026-07", 8, 0.25))
        val trend = mutableListOf(QuizTrendRow("2026-07-20", 0.5))
        val history = mutableListOf(WeeklyRecordRow("2026-W30", 5, 4, 0.8))

        val view = view(unlocked, newly, vocabulary, monthly, trend, history)
        unlocked.clear()
        newly.add(UnlockedAchievement("other", "x"))
        vocabulary.clear()
        monthly.clear()
        trend.clear()
        history.clear()

        assertEquals(listOf("streak_7"), view.unlockedAchievements.map { it.id })
        assertEquals(listOf("streak_7"), view.newlyUnlockedAchievements.map { it.id })
        assertEquals(listOf("term"), view.recentVocabulary.map { it.term })
        assertEquals(listOf("2026-07"), view.monthlyActivity.map { it.month })
        assertEquals(listOf("2026-07-20"), view.recentQuizTrend.map { it.gradedAt })
        assertEquals(listOf("2026-W30"), checkNotNull(view.weeklyGoal).history.map { it.week })
    }

    @Test
    fun `android-check-06 公開された6つのlistはcastしても書き換えられない`() {
        val view = view()

        val exposed: List<MutableList<Any?>> = listOf(
            view.unlockedAchievements.asMutable(),
            view.newlyUnlockedAchievements.asMutable(),
            view.recentVocabulary.asMutable(),
            view.monthlyActivity.asMutable(),
            view.recentQuizTrend.asMutable(),
            checkNotNull(view.weeklyGoal).history.asMutable(),
        )
        for (list in exposed) {
            assertThrows(UnsupportedOperationException::class.java) { list.clear() }
        }

        assertEquals(listOf("streak_7"), view.unlockedAchievements.map { it.id })
        assertEquals(listOf("term"), view.recentVocabulary.map { it.term })
        assertEquals(listOf("2026-W30"), checkNotNull(view.weeklyGoal).history.map { it.week })
    }

    @Suppress("UNCHECKED_CAST")
    private fun List<*>.asMutable(): MutableList<Any?> = this as MutableList<Any?>
}
