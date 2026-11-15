package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.learning.app.AchievementRecord
import com.rioikeda.newslisten.learning.app.DashboardRecord
import com.rioikeda.newslisten.learning.app.LearningApi
import com.rioikeda.newslisten.learning.app.MonthlyActivityRecord
import com.rioikeda.newslisten.learning.app.QuizTrendRecord
import com.rioikeda.newslisten.learning.app.StreakRecord
import com.rioikeda.newslisten.learning.app.VocabularyRecord
import com.rioikeda.newslisten.learning.app.VocabularySummary
import com.rioikeda.newslisten.learning.app.WeeklyGoalRecord
import com.rioikeda.newslisten.learning.app.WeeklyHistoryRecord

/** [ApiClient] の学習系の読みを包み、DTO を port の部品に変える。例外は呼び手へそのまま伝える。 */
internal class LearningApiAdapter(private val apiClient: ApiClient) : LearningApi {
    override suspend fun fetchDashboard(): DashboardRecord {
        val response = apiClient.fetchLearningDashboard()
        return DashboardRecord(
            streak = StreakRecord(
                days = response.streak.currentStreakDays,
                todayListened = response.streak.todayListened,
                lastListenedDay = response.streak.lastListenedDay,
            ),
            totalEpisodes = response.totalEpisodes,
            vocabularyAcquired = response.vocabularyAcquired,
            quizzedEpisodes = response.quiz.quizzedEpisodes,
            averageCorrectRate = response.quiz.averageCorrectRate,
            quizTrend = response.quiz.trend.map { QuizTrendRecord(it.gradedAt, it.correctRate) },
            monthlyActivity = response.monthlyActivity.map { MonthlyActivityRecord(it.month, it.activeDays) },
            currentDifficulty = response.currentDifficulty,
            weeklyGoal = response.weeklyGoal?.let { goal ->
                WeeklyGoalRecord(
                    goalEpisodes = goal.goalEpisodes,
                    completedThisWeek = goal.completedThisWeek,
                    history = goal.history.map { WeeklyHistoryRecord(it.week, it.goal, it.completed) },
                )
            },
            achievements = response.achievements.orEmpty().map { AchievementRecord(it.id, it.unlockedAt) },
        )
    }

    override suspend fun fetchStreak(): StreakRecord {
        val response = apiClient.fetchListeningStreak()
        return StreakRecord(response.currentStreakDays, response.todayListened, response.lastListenedDay)
    }

    override suspend fun hasVocabularyTest(): Boolean = apiClient.fetchVocabularyTestSession().items.isNotEmpty()

    override suspend fun fetchVocabularySummary(): VocabularySummary {
        val response = apiClient.fetchVocabulary()
        return VocabularySummary(
            count = response.count,
            items = response.vocabulary.map { VocabularyRecord(it.term, it.meaning, it.registeredAt) },
        )
    }
}
