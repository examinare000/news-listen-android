package com.rioikeda.newslisten.learning.app

import com.rioikeda.newslisten.learning.domain.RetestFeedback
import com.rioikeda.newslisten.learning.domain.VocabularyTestPhase
import com.rioikeda.newslisten.learning.domain.VocabularyTestProgress
import com.rioikeda.newslisten.learning.domain.VocabularyTestSummary
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
    val fraction: Float,
    history: List<WeeklyRecordRow>,
) {
    val history: List<WeeklyRecordRow> = Collections.unmodifiableList(ArrayList(history))
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

/** 単語テストの画面が読む項目だけを持つ view。集合は渡された list と共有しない読み取り専用の写し。 */
class VocabularyTestView(
    val phase: VocabularyTestPhase,
    val progress: VocabularyTestProgress?,
    val currentTerm: String?,
    val currentExample: String?,
    val correctMeaning: String?,
    choices: List<String>,
    val retestFeedback: RetestFeedback?,
    val isAnswerLocked: Boolean,
    val summary: VocabularyTestSummary?,
) {
    val choices: List<String> = Collections.unmodifiableList(ArrayList(choices))

    // 等しい状態を StateFlow が再発行しないために、9 項目すべてで決める。
    override fun equals(other: Any?): Boolean =
        other is VocabularyTestView &&
            phase == other.phase &&
            progress == other.progress &&
            currentTerm == other.currentTerm &&
            currentExample == other.currentExample &&
            correctMeaning == other.correctMeaning &&
            choices == other.choices &&
            retestFeedback == other.retestFeedback &&
            isAnswerLocked == other.isAnswerLocked &&
            summary == other.summary

    override fun hashCode(): Int {
        var result = phase.hashCode()
        result = 31 * result + (progress?.hashCode() ?: 0)
        result = 31 * result + (currentTerm?.hashCode() ?: 0)
        result = 31 * result + (currentExample?.hashCode() ?: 0)
        result = 31 * result + (correctMeaning?.hashCode() ?: 0)
        result = 31 * result + choices.hashCode()
        result = 31 * result + (retestFeedback?.hashCode() ?: 0)
        result = 31 * result + isAnswerLocked.hashCode()
        result = 31 * result + (summary?.hashCode() ?: 0)
        return result
    }
}
