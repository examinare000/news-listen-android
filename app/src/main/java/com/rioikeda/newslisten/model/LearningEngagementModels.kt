package com.rioikeda.newslisten.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class QuizTrendPointResponse(
    @SerialName("graded_at") val gradedAt: String,
    @SerialName("correct_rate") val correctRate: Double,
)

@Serializable
data class QuizStatsResponse(
    @SerialName("quizzed_episodes") val quizzedEpisodes: Int,
    @SerialName("average_correct_rate") val averageCorrectRate: Double?,
    val trend: List<QuizTrendPointResponse>,
)

@Serializable
data class MonthlyActivityResponse(
    val month: String,
    @SerialName("active_days") val activeDays: Int,
)

@Serializable
data class WeeklyGoalRecordResponse(
    val week: String,
    val goal: Int,
    val completed: Int,
)

@Serializable
data class WeeklyGoalResponse(
    @SerialName("goal_episodes") val goalEpisodes: Int,
    val week: String,
    @SerialName("completed_this_week") val completedThisWeek: Int,
    val history: List<WeeklyGoalRecordResponse>,
) {
    val progressText: String
        get() = "今週 $completedThisWeek/目標 $goalEpisodes 本"

    val progressFraction: Float
        get() = if (goalEpisodes <= 0) 0f else (completedThisWeek.toFloat() / goalEpisodes).coerceIn(0f, 1f)
}

@Serializable
data class AchievementResponse(
    val id: String,
    @SerialName("unlocked_at") val unlockedAt: String,
)

@Serializable
data class LearningDashboardResponse(
    val streak: ListeningStreakResponse,
    @SerialName("total_episodes") val totalEpisodes: Int,
    @SerialName("vocabulary_acquired") val vocabularyAcquired: Int,
    val quiz: QuizStatsResponse,
    @SerialName("monthly_activity") val monthlyActivity: List<MonthlyActivityResponse>,
    @SerialName("current_difficulty") val currentDifficulty: String,
    @SerialName("weekly_goal") val weeklyGoal: WeeklyGoalResponse? = null,
    val achievements: List<AchievementResponse>? = null,
)
