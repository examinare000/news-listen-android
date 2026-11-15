package com.rioikeda.newslisten.learning.app

/**
 * 学習タブの読みの port。実装は失敗を例外のまま呼び手へ伝える。
 *
 * 戻り値はこのファイルの部品だけ。DTO・JSON・リードモデルは出さない。
 */
interface LearningApi {
    suspend fun fetchDashboard(): DashboardRecord

    suspend fun fetchStreak(): StreakRecord

    /** 単語テストの出題が 1 件以上あるか。 */
    suspend fun hasVocabularyTest(): Boolean

    /** 件数と語彙の全件。件数を絞るのは呼び手の責務。 */
    suspend fun fetchVocabularySummary(): VocabularySummary
}

data class StreakRecord(
    val days: Int,
    val todayListened: Boolean,
    val lastListenedDay: String?,
)

data class QuizTrendRecord(
    val gradedAt: String,
    val correctRate: Double,
)

data class MonthlyActivityRecord(
    val month: String,
    val activeDays: Int,
)

data class WeeklyHistoryRecord(
    val week: String,
    val goal: Int,
    val completed: Int,
)

data class WeeklyGoalRecord(
    val goalEpisodes: Int,
    val completedThisWeek: Int,
    val history: List<WeeklyHistoryRecord>,
)

data class AchievementRecord(
    val id: String,
    val unlockedAt: String,
)

data class DashboardRecord(
    val streak: StreakRecord,
    val totalEpisodes: Int,
    val vocabularyAcquired: Int,
    val quizzedEpisodes: Int,
    val averageCorrectRate: Double?,
    val quizTrend: List<QuizTrendRecord>,
    val monthlyActivity: List<MonthlyActivityRecord>,
    val currentDifficulty: String,
    val weeklyGoal: WeeklyGoalRecord?,
    val achievements: List<AchievementRecord>,
)

data class VocabularyRecord(
    val term: String,
    val meaning: String,
    val registeredAt: String,
)

data class VocabularySummary(
    val count: Int,
    val items: List<VocabularyRecord>,
)
