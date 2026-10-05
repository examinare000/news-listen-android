package com.rioikeda.newslisten.architecture

/**
 * 許可リストの 1 組。`(規則 ID, ファイル, 対象)` が違反の単位で、`owner` は消す slice の ID（記録。合否には効かない）。
 * 同じ組に当たる行が何行あっても 1 組と数える（行の増加は検出しない。限界）。
 */
data class AllowEntry(val rule: String, val file: String, val target: String, val owner: String) {
    val key: Triple<String, String, String> get() = Triple(rule, file, target)
}

/**
 * 今の違反の固定（Spec §8.4 A-T1。order §3.1 の 115 組）。以後の slice は、違反を消したら組を消す。
 * 走査の出力から生成しない。差が出たら、走査と仕様のどちらが誤りかを先に決める。
 *
 * 持ち主の導出規則: その組を成す行をすべて消す slice のうち、§8.1 の順で最も後のもの。
 * TA-V8 のファイルは test の根からの相対 path。
 */
object Allowlist {

    private fun rule(ruleId: String, target: String, owner: String, vararg files: String) =
        files.map { AllowEntry(ruleId, it, target, owner) }

    val entries: List<AllowEntry> =
        // TA-D1 (3)
        rule("TA-D1", "model", "A-T2b", "podcast/PlaybackSession.kt") +
            rule("TA-D1", "model", "A-T6a", "auth/AuthState.kt") +
            rule("TA-D1", "androidx", "A-T3a1", "podcast/PlaybackState.kt") +

            // TA-D2 (30)
            rule(
                "TA-D2", "model", "A-T2b",
                "podcast/PlaybackSession.kt",
                "podcast/PodcastStatusBadge.kt",
                "podcast/PlaybackMetadata.kt",
                "podcast/PodcastRowView.kt",
                "podcast/PodcastScreen.kt",
                "podcast/QueueSheet.kt",
                "podcast/AudioPlayerSection.kt",
            ) +
            rule("TA-D2", "model", "A-T6a", "auth/AuthState.kt", "auth/AuthViewModel.kt") +
            rule(
                "TA-D2", "model", "A-T6b",
                "account/SessionsViewModel.kt",
                "passkey/PasskeyCredentialsViewModel.kt",
                "passkey/PasskeyLoginViewModel.kt",
                "passkey/PasskeyRegistrationViewModel.kt",
            ) +
            rule(
                "TA-D2", "model", "A-T7a",
                "learning/LearningViewModel.kt",
                "learning/LearningScreen.kt",
                "engagement/ListeningStreakStore.kt",
                "AppScaffold.kt",
            ) +
            rule("TA-D2", "model", "A-T7b1", "podcast/QuizSheet.kt", "podcast/PodcastViewModel.kt") +
            rule("TA-D2", "model", "A-T7b2", "vocabulary/VocabularyTestViewModel.kt") +
            rule(
                "TA-D2", "model", "A-T8a",
                "feed/FeedViewModel.kt",
                "feed/FeedScreen.kt",
                "feed/PendingArticleAction.kt",
            ) +
            rule(
                "TA-D2", "model", "A-T8b",
                "settings/SettingsViewModel.kt",
                "settings/SettingsScreen.kt",
                "onboarding/OnboardingViewModel.kt",
                "onboarding/OnboardingScreen.kt",
            ) +
            rule("TA-D2", "core.QueueItem", "A-T2b", "model/PodcastResponse.kt") +
            rule("TA-D2", "androidx", "A-T8b", "model/FeaturedCategory.kt") +
            rule("TA-D2", "R", "A-T8b", "model/FeaturedCategory.kt") +

            // TA-D3 (31): network 14 + model 17
            rule(
                "TA-D3", "network", "A-T6b",
                "account/AccountViewModel.kt",
                "account/SessionsViewModel.kt",
                "passkey/PasskeyCredentialsViewModel.kt",
                "passkey/PasskeyLoginViewModel.kt",
                "passkey/PasskeyRegistrationViewModel.kt",
            ) +
            rule("TA-D3", "network", "A-T7a", "engagement/ListeningStreakStore.kt") +
            rule("TA-D3", "network", "A-T8a", "feed/FeedViewModel.kt") +
            rule(
                "TA-D3", "network", "A-T8b",
                "notification/FcmTokenRegistrar.kt",
                "onboarding/OnboardingViewModel.kt",
                "settings/SettingsViewModel.kt",
            ) +
            // port の置き場を移すのが A-T9（Spec §3.2）
            rule(
                "TA-D3", "network", "A-T9",
                "auth/AuthViewModel.kt",
                "learning/LearningViewModel.kt",
                "podcast/PodcastViewModel.kt",
                "vocabulary/VocabularyTestViewModel.kt",
            ) +
            rule(
                "TA-D3", "model", "A-T6b",
                "account/SessionsViewModel.kt",
                "passkey/PasskeyCredentialsViewModel.kt",
                "passkey/PasskeyLoginViewModel.kt",
                "passkey/PasskeyRegistrationViewModel.kt",
            ) +
            rule("TA-D3", "model", "A-T6a", "auth/AuthViewModel.kt") +
            rule("TA-D3", "model", "A-T7a", "engagement/ListeningStreakStore.kt", "learning/LearningViewModel.kt") +
            rule("TA-D3", "model", "A-T7b1", "podcast/PodcastViewModel.kt") +
            rule("TA-D3", "model", "A-T7b2", "vocabulary/VocabularyTestViewModel.kt") +
            rule("TA-D3", "model", "A-T8a", "feed/FeedViewModel.kt", "feed/PendingArticleAction.kt") +
            rule("TA-D3", "model", "A-T8b", "onboarding/OnboardingViewModel.kt", "settings/SettingsViewModel.kt") +
            rule("TA-D3", "model", "A-T2b", "podcast/PlaybackMetadata.kt", "network/PodcastApi.kt") +
            rule("TA-D3", "model", "A-T7b2", "network/LearningApi.kt", "network/VocabularyTestApi.kt") +

            // TA-D4 (17)
            rule(
                "TA-D4", "model", "A-T2b",
                "podcast/AudioPlayerSection.kt",
                "podcast/PodcastRowView.kt",
                "podcast/PodcastScreen.kt",
                "podcast/PodcastStatusBadge.kt",
                "podcast/QueueSheet.kt",
            ) +
            rule("TA-D4", "model", "A-T7a", "AppScaffold.kt", "learning/LearningScreen.kt") +
            rule("TA-D4", "model", "A-T7b1", "podcast/QuizSheet.kt") +
            rule("TA-D4", "model", "A-T8a", "feed/FeedScreen.kt") +
            rule("TA-D4", "model", "A-T8b", "onboarding/OnboardingScreen.kt", "settings/SettingsScreen.kt") +
            // QuizSheet の network import は ApiException だけ。A-T4 が ApiException を core へ移すと変わる。
            rule("TA-D4", "network", "A-T4", "podcast/QuizSheet.kt") +
            rule(
                "TA-D4", "PreferencesStore", "A-T5b",
                "MainActivity.kt",
                "AppScaffold.kt",
                "designsystem/DSFeedback.kt",
                "settings/SettingsScreen.kt",
            ) +
            rule("TA-D4", "preferencesStore.set", "A-T5b", "settings/SettingsScreen.kt") +

            // TA-D6 (1)
            rule("TA-D6", "== \"", "A-T6a", "di/AppContainer.kt") +

            // TA-D7 (3)
            rule("TA-D7", "公開の var", "A-S2c", "podcast/PlayerController.kt") +
            rule("TA-D7", "公開の var", "A-T7a", "engagement/ListeningStreakStore.kt") +
            rule("TA-D7", "公開の var", "A-T8a", "feed/FeedViewModel.kt") +

            // TA-D9 (8)
            rule(
                "TA-D9", "e.code", "A-T4",
                "account/AccountViewModel.kt",
                "settings/SettingsViewModel.kt",
                "engagement/ListeningStreakStore.kt",
                "onboarding/OnboardingViewModel.kt",
                "passkey/PasskeyRegistrationViewModel.kt",
                "podcast/QuizSheet.kt",
            ) +
            rule("TA-D9", "= e.message", "A-T8a", "feed/FeedViewModel.kt") +
            rule("TA-D9", "= e.message", "A-T3b", "podcast/PodcastViewModel.kt") +

            // TA-V2 (8)
            rule("TA-V2", "fetchPodcasts", "A-T2b", "network/PodcastApi.kt") +
            rule("TA-V2", "fetchPodcast", "A-T2b", "network/PodcastApi.kt") +
            rule("TA-V2", "updatePlaybackPosition", "A-T2b", "network/PodcastApi.kt") +
            rule("TA-V2", "fetchLearningDashboard", "A-T7a", "network/LearningApi.kt") +
            rule("TA-V2", "fetchVocabulary", "A-T7b1", "network/LearningApi.kt") +
            rule("TA-V2", "fetchVocabularyTestSession", "A-T7b2", "network/LearningApi.kt") +
            rule("TA-V2", "fetchVocabularyTestSession", "A-T7b2", "network/VocabularyTestApi.kt") +
            rule("TA-V2", "submitVocabularyTestResults", "A-T7b2", "network/VocabularyTestApi.kt") +

            // TA-V4 (13)
            rule("TA-V4", "\"processing\"", "A-T2b", "podcast/PodcastStatusBadge.kt") +
            rule("TA-V4", "\"failed\"", "A-T2b", "podcast/PodcastStatusBadge.kt") +
            rule("TA-V4", "\"partial_failed\"", "A-T2b", "podcast/PodcastStatusBadge.kt") +
            rule("TA-V4", "PLAYBACK_SPEEDS", "A-T5b", "settings/SettingsScreen.kt") +
            rule("TA-V4", "listOf(3, 5, 7, 10)", "A-T5b", "settings/SettingsScreen.kt") +
            rule("TA-V4", "/ 7.0", "A-T5b", "settings/SettingsScreen.kt") +
            rule("TA-V4", "setOf(3, 5, 7, 10)", "A-T5a", "settings/SettingsViewModel.kt") +
            rule("TA-V4", "/ 31.0", "A-T7a", "learning/LearningScreen.kt") +
            rule("TA-V4", "correctRate >=", "A-T7b1", "podcast/QuizSheet.kt") +
            rule("TA-V4", "limit == 0", "A-T8b", "settings/SettingsScreen.kt") +
            rule("TA-V4", "moveUpNext(index", "A-T3a2", "podcast/QueueSheet.kt") +
            rule("TA-V4", "role ==", "A-T6a", "AppScaffold.kt", "di/AppContainer.kt") +

            // TA-V8 (1)
            rule("TA-V8", "androidx", "A-T3a1", "podcast/PlaybackStateTest.kt")
}
