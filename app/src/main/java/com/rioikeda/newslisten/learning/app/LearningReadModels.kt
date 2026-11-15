package com.rioikeda.newslisten.learning.app

import java.util.ArrayList
import java.util.Collections

/** 事象は音を鳴らす側（presentation）が写す。実績の解錠は今は鳴らさないので出さない。 */
enum class LearningFeedbackEvent { STREAK_INCREASED }

/**
 * `load()` が読んだ時点の主体の印。presentation は中身を読まず、既読を保存する command へ渡すだけ。
 */
class ReadSubject internal constructor(internal val subjectId: String?)

data class UnlockedAchievement(val id: String, val unlockedAt: String)

data class VocabularyRow(val term: String, val meaning: String, val registeredAt: String)

data class QuizTrendRow(val gradedAt: String, val correctRate: Double)

data class MonthlyActivityRow(val month: String, val activeDays: Int, val rate: Double)

data class WeeklyRecordRow(val week: String, val goal: Int, val completed: Int, val rate: Double)

/** 今週の目標の進み。`history` は渡された list と共有しない読み取り専用の写し。 */
class WeeklyGoalProgress(
    val goalEpisodes: Int,
    val completedThisWeek: Int,
    history: List<WeeklyRecordRow>,
) {
    val history: List<WeeklyRecordRow> = Collections.unmodifiableList(ArrayList(history))

    val fraction: Float =
        if (goalEpisodes <= 0) 0f else (completedThisWeek.toFloat() / goalEpisodes).coerceIn(0f, 1f)
}

/** ストリークの表示用の値。`visible` の規則の正本は [StreakQuery]（android-rule-learning-04）。 */
data class StreakBadge(
    val days: Int,
    val todayListened: Boolean,
    val lastListenedDay: String?,
    val visible: Boolean,
)

/** 学習ダッシュボードのリードモデル。集合は渡された list と共有しない読み取り専用の写し。 */
class LearningDashboardView(
    val streakDays: Int,
    val totalEpisodes: Int,
    val vocabularyAcquired: Int,
    val quizzedEpisodes: Int,
    val averageCorrectRate: Double?,
    val currentDifficulty: String,
    val weeklyGoal: WeeklyGoalProgress?,
    monthlyActivity: List<MonthlyActivityRow>,
    recentQuizTrend: List<QuizTrendRow>,
    unlockedAchievements: List<UnlockedAchievement>,
    newlyUnlockedAchievements: List<UnlockedAchievement>,
    val vocabularyCount: Int,
    recentVocabulary: List<VocabularyRow>,
    val hasVocabularyTest: Boolean,
    val readAs: ReadSubject,
) {
    val monthlyActivity: List<MonthlyActivityRow> = Collections.unmodifiableList(ArrayList(monthlyActivity))
    val recentQuizTrend: List<QuizTrendRow> = Collections.unmodifiableList(ArrayList(recentQuizTrend))
    val unlockedAchievements: List<UnlockedAchievement> = Collections.unmodifiableList(ArrayList(unlockedAchievements))
    val newlyUnlockedAchievements: List<UnlockedAchievement> =
        Collections.unmodifiableList(ArrayList(newlyUnlockedAchievements))
    val recentVocabulary: List<VocabularyRow> = Collections.unmodifiableList(ArrayList(recentVocabulary))
}
