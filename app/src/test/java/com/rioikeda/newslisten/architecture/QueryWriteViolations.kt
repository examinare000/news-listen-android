package com.rioikeda.newslisten.architecture

/** 移行違反 1 件。`rule` は常に `android-dep-08`。`owner`・`fixTask`・`removeBy` は [SliceIds.all] の要素。 */
data class MigrationViolation(
    val operationId: String,
    val port: String,
    val op: String,
    val path: String,
    val rule: String = RULE,
    val owner: String,
    val fixTask: String,
    val removeBy: String,
) {
    companion object {
        const val RULE = "android-dep-08"
    }
}

/** 既知の違反。目標 Spec §6 から来る組で、違反の表に含まれ、かつ観測されなければならない。 */
data class KnownViolation(
    val operationId: String,
    val port: String,
    val op: String,
    val source: String,
    val removeBy: String,
)

object QueryWriteViolations {
    private const val LEARNING = "android-slice-learning-01"
    private const val PERIPHERY = "android-slice-periphery-01"

    private fun violation(operationId: String, port: String, op: String, path: String, owner: String) =
        MigrationViolation(operationId, port, op, path, owner = owner, fixTask = owner, removeBy = owner)

    /**
     * 観測と一致させる表。query が書く `(操作 ID, port, op)` を、owner の slice が消すまで固定する（両方向の一致で縮め忘れも落とす）。
     * 各 owner の slice は、直した行をこの表と [known] から消す。
     */
    val table: List<MigrationViolation> = listOf(
        // 目標 Spec §6 #1: ダッシュボードの読み込みの中で、解錠済みの実績を既読として保存する。
        violation("LearningViewModel#load", "PreferencesStore", "markAchievementsSeen", "learning/LearningViewModel.kt", LEARNING),
        // 目標 Spec §6 #2: 読む前に、保留中の Star・Dismiss を確定送信する。
        violation("FeedViewModel#loadFeed", "ApiClient", "starArticle", "feed/FeedViewModel.kt", PERIPHERY),
        violation("FeedViewModel#loadFeed", "ApiClient", "dismissArticle", "feed/FeedViewModel.kt", PERIPHERY),
        violation("FeedViewModel#refresh", "ApiClient", "starArticle", "feed/FeedViewModel.kt", PERIPHERY),
        violation("FeedViewModel#refresh", "ApiClient", "dismissArticle", "feed/FeedViewModel.kt", PERIPHERY),
        // 新規検出: 読み直しで聴取日数が増えたとき、公開 callback を発火する（android-check-05 の公開 `var` の owner）。
        violation("ListeningStreakStore#refresh", "hook", "ApiListeningStreakStore#onStreakIncreased", "engagement/ListeningStreakStore.kt", LEARNING),
        // 新規検出: 上の store の読み直しを呼ぶ query。委譲先の発火が呼んだ query に帰属する。
        violation("SettingsViewModel#loadListeningStreak", "hook", "ApiListeningStreakStore#onStreakIncreased", "settings/SettingsViewModel.kt", LEARNING),
    )

    /** 観測より先に固定する既知の違反（目標 Spec §6 #1・#2）。消して通すことは禁止。 */
    val known: List<KnownViolation> = listOf(
        KnownViolation("LearningViewModel#load", "PreferencesStore", "markAchievementsSeen", "目標 Spec §6 #1", "android-slice-learning-01"),
        KnownViolation("FeedViewModel#loadFeed", "ApiClient", "starArticle", "目標 Spec §6 #2", "android-slice-periphery-01"),
        KnownViolation("FeedViewModel#loadFeed", "ApiClient", "dismissArticle", "目標 Spec §6 #2", "android-slice-periphery-01"),
        KnownViolation("FeedViewModel#refresh", "ApiClient", "starArticle", "目標 Spec §6 #2", "android-slice-periphery-01"),
        KnownViolation("FeedViewModel#refresh", "ApiClient", "dismissArticle", "目標 Spec §6 #2", "android-slice-periphery-01"),
    )
}
