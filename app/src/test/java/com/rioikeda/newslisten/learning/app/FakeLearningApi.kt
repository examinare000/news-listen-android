package com.rioikeda.newslisten.learning.app

import com.rioikeda.newslisten.preferences.ArticleOpenMode
import com.rioikeda.newslisten.preferences.InMemoryPreferencesStore
import com.rioikeda.newslisten.preferences.PreferencesStore
import com.rioikeda.newslisten.preferences.TimeFormat
import com.rioikeda.newslisten.core.Difficulty

/** 呼出の順を共有の記録へ積む [LearningApi] の fake。応答と失敗は操作ごとに差し替える。 */
class FakeLearningApi(private val log: MutableList<String> = mutableListOf()) : LearningApi {
    var onFetchDashboard: suspend () -> DashboardRecord = { dashboardRecord() }
    var onFetchStreak: suspend () -> StreakRecord = { StreakRecord(0, false, null) }
    var onHasVocabularyTest: suspend () -> Boolean = { false }
    var onFetchVocabularySummary: suspend () -> VocabularySummary = { VocabularySummary(0, emptyList()) }

    override suspend fun fetchDashboard(): DashboardRecord {
        log += "fetchDashboard"
        return onFetchDashboard()
    }

    override suspend fun fetchStreak(): StreakRecord {
        log += "fetchStreak"
        return onFetchStreak()
    }

    override suspend fun hasVocabularyTest(): Boolean {
        log += "hasVocabularyTest"
        return onHasVocabularyTest()
    }

    override suspend fun fetchVocabularySummary(): VocabularySummary {
        log += "fetchVocabularySummary"
        return onFetchVocabularySummary()
    }
}

/** 書込の全 9 操作を共有の記録へ積む store。読みは [InMemoryPreferencesStore] のまま。 */
class RecordingPreferencesStore(
    private val log: MutableList<String> = mutableListOf(),
    val inner: InMemoryPreferencesStore = InMemoryPreferencesStore(),
) : PreferencesStore by inner {
    override suspend fun setDefaultDifficulty(difficulty: Difficulty) {
        log += "setDefaultDifficulty"
        inner.setDefaultDifficulty(difficulty)
    }

    override suspend fun setDefaultPlaybackSpeed(speed: Double) {
        log += "setDefaultPlaybackSpeed"
        inner.setDefaultPlaybackSpeed(speed)
    }

    override suspend fun setArticleOpenMode(mode: ArticleOpenMode) {
        log += "setArticleOpenMode"
        inner.setArticleOpenMode(mode)
    }

    override suspend fun setTimeFormat(format: TimeFormat) {
        log += "setTimeFormat"
        inner.setTimeFormat(format)
    }

    override suspend fun setSfxEnabled(enabled: Boolean) {
        log += "setSfxEnabled"
        inner.setSfxEnabled(enabled)
    }

    override suspend fun setHapticsEnabled(enabled: Boolean) {
        log += "setHapticsEnabled"
        inner.setHapticsEnabled(enabled)
    }

    override suspend fun setWeeklyGoalEpisodes(episodes: Int) {
        log += "setWeeklyGoalEpisodes"
        inner.setWeeklyGoalEpisodes(episodes)
    }

    override suspend fun markAchievementsSeen(ids: Set<String>) {
        log += "markAchievementsSeen"
        inner.markAchievementsSeen(ids)
    }

    override suspend fun clearSubjectScoped() {
        log += "clearSubjectScoped"
        inner.clearSubjectScoped()
    }
}

/** ダッシュボード応答の port の部品。既定は 1 週目 3/5 本・実績なし。 */
fun dashboardRecord(
    streak: StreakRecord = StreakRecord(4, true, "2026-07-29"),
    totalEpisodes: Int = 12,
    vocabularyAcquired: Int = 31,
    quizzedEpisodes: Int = 2,
    averageCorrectRate: Double? = 0.75,
    quizTrend: List<QuizTrendRecord> = emptyList(),
    monthlyActivity: List<MonthlyActivityRecord> = listOf(MonthlyActivityRecord("2026-07", 8)),
    currentDifficulty: String = "toeic_900",
    weeklyGoal: WeeklyGoalRecord? = WeeklyGoalRecord(goalEpisodes = 5, completedThisWeek = 3, history = emptyList()),
    achievements: List<AchievementRecord> = emptyList(),
) = DashboardRecord(
    streak = streak,
    totalEpisodes = totalEpisodes,
    vocabularyAcquired = vocabularyAcquired,
    quizzedEpisodes = quizzedEpisodes,
    averageCorrectRate = averageCorrectRate,
    quizTrend = quizTrend,
    monthlyActivity = monthlyActivity,
    currentDifficulty = currentDifficulty,
    weeklyGoal = weeklyGoal,
    achievements = achievements,
)
