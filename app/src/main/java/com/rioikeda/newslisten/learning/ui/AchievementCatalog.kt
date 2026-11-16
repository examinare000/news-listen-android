package com.rioikeda.newslisten.learning.ui

import com.rioikeda.newslisten.learning.domain.Achievement

data class AchievementCatalogItem(
    val id: String,
    val name: String,
    val description: String,
)

/** 表示順は [Achievement.ids] と同じ。id は domain の定数を使い、名前と説明だけを presentation が持つ。 */
val AchievementCatalog = listOf(
    AchievementCatalogItem(
        Achievement.FIRST_EPISODE_COMPLETED,
        "初回エピソード完聴",
        "はじめてエピソードを最後まで聴く",
    ),
    AchievementCatalogItem(
        Achievement.FIRST_QUIZ_CORRECT,
        "初回クイズ正解",
        "はじめて理解度クイズに正解する",
    ),
    AchievementCatalogItem(Achievement.STREAK_7, "7 日連続聴取", "7 日間連続で聴く"),
    AchievementCatalogItem(Achievement.STREAK_30, "30 日連続聴取", "30 日間連続で聴く"),
    AchievementCatalogItem(Achievement.STREAK_100, "100 日連続聴取", "100 日間連続で聴く"),
    AchievementCatalogItem(Achievement.COMPLETED_10, "累計 10 本完聴", "エピソードを累計 10 本聴き終える"),
    AchievementCatalogItem(Achievement.COMPLETED_50, "累計 50 本完聴", "エピソードを累計 50 本聴き終える"),
)
