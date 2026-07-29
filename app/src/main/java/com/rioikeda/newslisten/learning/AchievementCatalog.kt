package com.rioikeda.newslisten.learning

data class AchievementCatalogItem(
    val id: String,
    val name: String,
    val description: String,
)

val AchievementCatalog = listOf(
    AchievementCatalogItem(
        "first_episode_completed",
        "初回エピソード完聴",
        "はじめてエピソードを最後まで聴く",
    ),
    AchievementCatalogItem(
        "first_quiz_correct",
        "初回クイズ正解",
        "はじめて理解度クイズに正解する",
    ),
    AchievementCatalogItem("streak_7", "7 日連続聴取", "7 日間連続で聴く"),
    AchievementCatalogItem("streak_30", "30 日連続聴取", "30 日間連続で聴く"),
    AchievementCatalogItem("streak_100", "100 日連続聴取", "100 日間連続で聴く"),
    AchievementCatalogItem("completed_10", "累計 10 本完聴", "エピソードを累計 10 本聴き終える"),
    AchievementCatalogItem("completed_50", "累計 50 本完聴", "エピソードを累計 50 本聴き終える"),
)
