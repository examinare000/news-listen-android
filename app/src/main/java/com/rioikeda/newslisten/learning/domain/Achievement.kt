package com.rioikeda.newslisten.learning.domain

/** 実績の id と、新しく解錠された実績の判定（android-rule-learning-06）。名前と説明は presentation が持つ。 */
object Achievement {
    const val FIRST_EPISODE_COMPLETED = "first_episode_completed"
    const val FIRST_QUIZ_CORRECT = "first_quiz_correct"
    const val STREAK_7 = "streak_7"
    const val STREAK_30 = "streak_30"
    const val STREAK_100 = "streak_100"
    const val COMPLETED_10 = "completed_10"
    const val COMPLETED_50 = "completed_50"

    val ids: List<String> = listOf(
        FIRST_EPISODE_COMPLETED,
        FIRST_QUIZ_CORRECT,
        STREAK_7,
        STREAK_30,
        STREAK_100,
        COMPLETED_10,
        COMPLETED_50,
    )

    /** 解錠済みの順を保ち、既読に無いものだけを返す。カタログに無い id も残す。 */
    fun newlyUnlocked(unlockedIds: List<String>, seenIds: Set<String>): List<String> =
        unlockedIds.filterNot { it in seenIds }
}
