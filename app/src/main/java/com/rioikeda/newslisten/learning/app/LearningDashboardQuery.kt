package com.rioikeda.newslisten.learning.app

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.learning.domain.Achievement
import com.rioikeda.newslisten.learning.domain.MonthlyActivity
import com.rioikeda.newslisten.preferences.app.PreferencesStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 学習ダッシュボードの読み（android-query-learning-01）。書かない。既読の保存は [LearningCommands] が行う。
 *
 * 「新しく解錠された実績」は `load()` の時点で固定し、既読の保存の後も次の `load()` まで作り直さない。
 */
class LearningDashboardQuery(
    private val api: LearningApi,
    private val preferencesStore: PreferencesStore,
    private val currentSubject: () -> String?,
    private val dispatcher: CoroutineDispatcher,
) {
    private val _view = MutableStateFlow<LearningDashboardView?>(null)
    val view: StateFlow<LearningDashboardView?> = _view.asStateFlow()

    /** 読みが済んだ view を返す。ダッシュボードの読みが [ApiException] で失敗したら `null`（`view` は直前のまま）。 */
    suspend fun load(): LearningDashboardView? = withContext(dispatcher) {
        val readAs = ReadSubject(currentSubject())
        val dashboard = try {
            api.fetchDashboard()
        } catch (_: ApiException) {
            return@withContext null
        }
        val vocabulary = try {
            api.fetchVocabularySummary()
        } catch (_: ApiException) {
            null
        }
        val hasVocabularyTest = try {
            api.hasVocabularyTest()
        } catch (_: ApiException) {
            false
        }
        val unlocked = dashboard.achievements.map { UnlockedAchievement(it.id, it.unlockedAt) }
        val newlyIds = Achievement.newlyUnlocked(unlocked.map { it.id }, preferencesStore.seenAchievementIds.value).toSet()

        val view = LearningDashboardView(
            streakDays = dashboard.streak.days,
            totalEpisodes = dashboard.totalEpisodes,
            vocabularyAcquired = dashboard.vocabularyAcquired,
            quizzedEpisodes = dashboard.quizzedEpisodes,
            averageCorrectRate = dashboard.averageCorrectRate,
            currentDifficulty = dashboard.currentDifficulty,
            weeklyGoal = dashboard.weeklyGoal?.let { goal ->
                WeeklyGoalProgress(
                    goalEpisodes = goal.goalEpisodes,
                    completedThisWeek = goal.completedThisWeek,
                    fraction = weeklyGoalFraction(goal.goalEpisodes, goal.completedThisWeek),
                    history = goal.history.map {
                        WeeklyRecordRow(
                            week = it.week,
                            goal = it.goal,
                            completed = it.completed,
                            rate = (it.completed.toDouble() / maxOf(it.goal, 1)).coerceIn(0.0, 1.0),
                        )
                    },
                )
            },
            monthlyActivity = dashboard.monthlyActivity.map {
                MonthlyActivityRow(it.month, it.activeDays, MonthlyActivity.rate(it.activeDays))
            },
            recentQuizTrend = dashboard.quizTrend.takeLast(RECENT_QUIZ_TREND_LIMIT).map {
                QuizTrendRow(it.gradedAt, it.correctRate)
            },
            unlockedAchievements = unlocked,
            newlyUnlockedAchievements = unlocked.filter { it.id in newlyIds },
            vocabularyCount = vocabulary?.count ?: 0,
            recentVocabulary = vocabulary?.items.orEmpty().take(RECENT_VOCABULARY_LIMIT).map {
                VocabularyRow(it.term, it.meaning, it.registeredAt)
            },
            hasVocabularyTest = hasVocabularyTest,
            readAs = readAs,
        )
        _view.value = view
        view
    }

    private fun weeklyGoalFraction(goalEpisodes: Int, completedThisWeek: Int): Float =
        if (goalEpisodes <= 0) 0f else (completedThisWeek.toFloat() / goalEpisodes).coerceIn(0f, 1f)

    private companion object {
        const val RECENT_VOCABULARY_LIMIT = 5
        const val RECENT_QUIZ_TREND_LIMIT = 3
    }
}
