package com.rioikeda.newslisten.architecture

/**
 * 許可リストの 1 組。`(規則 ID, ファイル, 対象)` が違反の単位で、`owner` は消す slice の ID（記録。合否には効かない）。
 * 同じ組に当たる行が何行あっても 1 組と数える（行の増加は検出しない。限界）。
 */
data class AllowEntry(val rule: String, val file: String, val target: String, val owner: String) {
    val key: Triple<String, String, String> get() = Triple(rule, file, target)
}

/**
 * 今の違反の固定（Spec §8.4 android-slice-skeleton-01。order §3.1 の 115 組）。以後の slice は、違反を消したら組を消す。
 * 走査の出力から生成しない。差が出たら、走査と仕様のどちらが誤りかを先に決める。
 * android-slice-skeleton-03 で、`*ViewModel` の presentation 化（android-dec-09）に合わせて 24 組を消し 43 組を足した。
 *
 * 持ち主の導出規則: その組を成す行をすべて消す slice のうち、§8.1 の順で最も後のもの。
 * android-check-08 のファイルは test の根からの相対 path。
 */
object Allowlist {

    private fun rule(ruleId: String, target: String, owner: String, vararg files: String) =
        files.map { AllowEntry(ruleId, it, target, owner) }

    val entries: List<AllowEntry> =
        // android-dep-01 (3)
        rule("android-dep-01", "model", "android-slice-episode-02", "podcast/PlaybackSession.kt") +
            rule("android-dep-01", "model", "android-slice-account-01", "auth/AuthState.kt") +
            rule("android-dep-01", "androidx", "android-slice-playapp-01", "podcast/PlaybackState.kt") +

            // android-dep-02 (30)
            rule(
                "android-dep-02", "model", "android-slice-episode-02",
                "podcast/PlaybackSession.kt",
                "podcast/PodcastStatusBadge.kt",
                "podcast/PlaybackMetadata.kt",
                "podcast/PodcastRowView.kt",
                "podcast/PodcastScreen.kt",
                "podcast/QueueSheet.kt",
                "podcast/AudioPlayerSection.kt",
            ) +
            rule("android-dep-02", "model", "android-slice-account-01", "auth/AuthState.kt", "auth/AuthViewModel.kt") +
            rule(
                "android-dep-02", "model", "android-slice-account-02",
                "account/SessionsViewModel.kt",
                "passkey/PasskeyCredentialsViewModel.kt",
                "passkey/PasskeyLoginViewModel.kt",
                "passkey/PasskeyRegistrationViewModel.kt",
            ) +
            rule(
                "android-dep-02", "model", "android-slice-learning-01",
                "learning/LearningViewModel.kt",
                "learning/LearningScreen.kt",
                "engagement/ListeningStreakStore.kt",
                "AppScaffold.kt",
            ) +
            rule("android-dep-02", "model", "android-slice-learning-02", "podcast/QuizSheet.kt", "podcast/PodcastViewModel.kt") +
            rule("android-dep-02", "model", "android-slice-learning-03", "vocabulary/VocabularyTestViewModel.kt") +
            rule(
                "android-dep-02", "model", "android-slice-periphery-01",
                "feed/FeedViewModel.kt",
                "feed/FeedScreen.kt",
                "feed/PendingArticleAction.kt",
            ) +
            rule(
                "android-dep-02", "model", "android-slice-periphery-02",
                "settings/SettingsViewModel.kt",
                "settings/SettingsScreen.kt",
                "onboarding/OnboardingViewModel.kt",
                "onboarding/OnboardingScreen.kt",
            ) +
            rule("android-dep-02", "core.QueueItem", "android-slice-episode-02", "model/PodcastResponse.kt") +
            rule("android-dep-02", "androidx", "android-slice-periphery-02", "model/FeaturedCategory.kt") +
            rule("android-dep-02", "R", "android-slice-periphery-02", "model/FeaturedCategory.kt") +

            // android-dep-03 (8): network 2 + model 6
            rule("android-dep-03", "network", "android-slice-learning-01", "engagement/ListeningStreakStore.kt") +
            rule("android-dep-03", "network", "android-slice-periphery-02", "notification/FcmTokenRegistrar.kt") +
            rule("android-dep-03", "model", "android-slice-learning-01", "engagement/ListeningStreakStore.kt") +
            rule("android-dep-03", "model", "android-slice-periphery-01", "feed/PendingArticleAction.kt") +
            rule("android-dep-03", "model", "android-slice-episode-02", "podcast/PlaybackMetadata.kt", "network/PodcastApi.kt") +
            rule("android-dep-03", "model", "android-slice-learning-03", "network/LearningApi.kt", "network/VocabularyTestApi.kt") +

            // android-dep-04 (60)
            rule(
                "android-dep-04", "model", "android-slice-episode-02",
                "podcast/AudioPlayerSection.kt",
                "podcast/PodcastRowView.kt",
                "podcast/PodcastScreen.kt",
                "podcast/PodcastStatusBadge.kt",
                "podcast/QueueSheet.kt",
            ) +
            rule("android-dep-04", "model", "android-slice-learning-01", "AppScaffold.kt", "learning/LearningScreen.kt") +
            rule("android-dep-04", "model", "android-slice-learning-02", "podcast/QuizSheet.kt") +
            rule("android-dep-04", "model", "android-slice-periphery-01", "feed/FeedScreen.kt") +
            rule("android-dep-04", "model", "android-slice-periphery-02", "onboarding/OnboardingScreen.kt", "settings/SettingsScreen.kt") +
            // QuizSheet の network import は ApiException だけ。android-slice-failure-01 が ApiException を core へ移すと変わる。
            rule("android-dep-04", "network", "android-slice-failure-01", "podcast/QuizSheet.kt") +
            rule(
                "android-dep-04", "PreferencesStore", "android-slice-prefs-02",
                "MainActivity.kt",
                "AppScaffold.kt",
                "designsystem/DSFeedback.kt",
                "settings/SettingsScreen.kt",
            ) +
            rule("android-dep-04", "preferencesStore.set", "android-slice-prefs-02", "settings/SettingsScreen.kt") +
            rule("android-dep-04", "network", "android-slice-account-02", "account/AccountViewModel.kt", "account/SessionsViewModel.kt", "passkey/PasskeyCredentialsViewModel.kt", "passkey/PasskeyLoginViewModel.kt", "passkey/PasskeyRegistrationViewModel.kt") +
            rule("android-dep-04", "model", "android-slice-account-02", "account/SessionsViewModel.kt", "passkey/PasskeyCredentialsViewModel.kt", "passkey/PasskeyLoginViewModel.kt", "passkey/PasskeyRegistrationViewModel.kt") +
            rule("android-dep-04", "PasskeyProvider", "android-slice-account-02", "passkey/PasskeyLoginViewModel.kt", "passkey/PasskeyRegistrationViewModel.kt") +
            rule("android-dep-04", "model", "android-slice-account-01", "auth/AuthViewModel.kt") +
            rule("android-dep-04", "SessionStore", "android-slice-account-01", "auth/AuthViewModel.kt") +
            rule("android-dep-04", "network", "android-slice-account-01", "auth/AuthViewModel.kt") +
            rule("android-dep-04", "PreferencesStore", "android-slice-prefs-01", "auth/AuthViewModel.kt", "settings/SettingsViewModel.kt") +
            rule("android-dep-04", "preferencesStore.set", "android-slice-prefs-01", "auth/AuthViewModel.kt", "settings/SettingsViewModel.kt") +
            rule("android-dep-04", "model", "android-slice-periphery-01", "feed/FeedViewModel.kt") +
            rule("android-dep-04", "network", "android-slice-periphery-01", "feed/FeedViewModel.kt") +
            rule("android-dep-04", "PreferencesStore", "android-slice-periphery-01", "feed/FeedViewModel.kt") +
            rule("android-dep-04", "model", "android-slice-learning-01", "learning/LearningViewModel.kt") +
            rule("android-dep-04", "LearningApi", "android-slice-learning-01", "learning/LearningViewModel.kt") +
            rule("android-dep-04", "PreferencesStore", "android-slice-learning-01", "learning/LearningViewModel.kt") +
            rule("android-dep-04", "network", "android-slice-learning-01", "learning/LearningViewModel.kt") +
            rule("android-dep-04", "model", "android-slice-periphery-02", "onboarding/OnboardingViewModel.kt", "settings/SettingsViewModel.kt") +
            rule("android-dep-04", "network", "android-slice-periphery-02", "onboarding/OnboardingViewModel.kt", "settings/SettingsViewModel.kt") +
            rule("android-dep-04", "model", "android-slice-learning-02", "podcast/PodcastViewModel.kt") +
            rule("android-dep-04", "network", "android-slice-learning-02", "podcast/PodcastViewModel.kt") +
            rule("android-dep-04", "NetworkMonitoring", "android-slice-playapp-01", "podcast/PodcastViewModel.kt") +
            rule("android-dep-04", "PlayerController", "android-slice-playapp-01", "podcast/PodcastViewModel.kt") +
            rule("android-dep-04", "PodcastApi", "android-slice-playapp-01", "podcast/PodcastViewModel.kt") +
            rule("android-dep-04", "transition:add", "android-slice-playapp-01", "podcast/PodcastViewModel.kt") +
            rule("android-dep-04", "transition:advance", "android-slice-playapp-01", "podcast/PodcastViewModel.kt") +
            rule("android-dep-04", "transition:jump", "android-slice-playapp-01", "podcast/PodcastViewModel.kt") +
            rule("android-dep-04", "transition:moveUpNext", "android-slice-playapp-01", "podcast/PodcastViewModel.kt") +
            rule("android-dep-04", "transition:playNext", "android-slice-playapp-01", "podcast/PodcastViewModel.kt") +
            rule("android-dep-04", "transition:remove", "android-slice-playapp-01", "podcast/PodcastViewModel.kt") +
            rule("android-dep-04", "model", "android-slice-learning-03", "vocabulary/VocabularyTestViewModel.kt") +
            rule("android-dep-04", "VocabularyTestApi", "android-slice-learning-03", "vocabulary/VocabularyTestViewModel.kt") +
            rule("android-dep-04", "network", "android-slice-learning-03", "vocabulary/VocabularyTestViewModel.kt") +

            // android-dep-06 (1)
            rule("android-dep-06", "== \"", "android-slice-account-01", "di/AppContainer.kt") +

            // android-dep-07 (2)
            rule("android-dep-07", "公開の var", "android-slice-playback-05", "podcast/PlayerController.kt") +
            rule("android-dep-07", "公開の var", "android-slice-learning-01", "engagement/ListeningStreakStore.kt") +

            // android-dep-09 (8)
            rule(
                "android-dep-09", "e.code", "android-slice-failure-01",
                "account/AccountViewModel.kt",
                "settings/SettingsViewModel.kt",
                "engagement/ListeningStreakStore.kt",
                "onboarding/OnboardingViewModel.kt",
                "passkey/PasskeyRegistrationViewModel.kt",
                "podcast/QuizSheet.kt",
            ) +
            rule("android-dep-09", "= e.message", "android-slice-periphery-01", "feed/FeedViewModel.kt") +
            rule("android-dep-09", "= e.message", "android-slice-playapp-03", "podcast/PodcastViewModel.kt") +

            // android-check-02 (8)
            rule("android-check-02", "fetchPodcasts", "android-slice-episode-02", "network/PodcastApi.kt") +
            rule("android-check-02", "fetchPodcast", "android-slice-episode-02", "network/PodcastApi.kt") +
            rule("android-check-02", "updatePlaybackPosition", "android-slice-episode-02", "network/PodcastApi.kt") +
            rule("android-check-02", "fetchLearningDashboard", "android-slice-learning-01", "network/LearningApi.kt") +
            rule("android-check-02", "fetchVocabulary", "android-slice-learning-02", "network/LearningApi.kt") +
            rule("android-check-02", "fetchVocabularyTestSession", "android-slice-learning-03", "network/LearningApi.kt") +
            rule("android-check-02", "fetchVocabularyTestSession", "android-slice-learning-03", "network/VocabularyTestApi.kt") +
            rule("android-check-02", "submitVocabularyTestResults", "android-slice-learning-03", "network/VocabularyTestApi.kt") +

            // android-check-04 (13)
            rule("android-check-04", "\"processing\"", "android-slice-episode-02", "podcast/PodcastStatusBadge.kt") +
            rule("android-check-04", "\"failed\"", "android-slice-episode-02", "podcast/PodcastStatusBadge.kt") +
            rule("android-check-04", "\"partial_failed\"", "android-slice-episode-02", "podcast/PodcastStatusBadge.kt") +
            rule("android-check-04", "PLAYBACK_SPEEDS", "android-slice-prefs-02", "settings/SettingsScreen.kt") +
            rule("android-check-04", "listOf(3, 5, 7, 10)", "android-slice-prefs-02", "settings/SettingsScreen.kt") +
            rule("android-check-04", "/ 7.0", "android-slice-prefs-02", "settings/SettingsScreen.kt") +
            rule("android-check-04", "setOf(3, 5, 7, 10)", "android-slice-prefs-01", "settings/SettingsViewModel.kt") +
            rule("android-check-04", "/ 31.0", "android-slice-learning-01", "learning/LearningScreen.kt") +
            rule("android-check-04", "correctRate >=", "android-slice-learning-02", "podcast/QuizSheet.kt") +
            rule("android-check-04", "limit == 0", "android-slice-periphery-02", "settings/SettingsScreen.kt") +
            rule("android-check-04", "moveUpNext(index", "android-slice-playapp-02", "podcast/QueueSheet.kt") +
            rule("android-check-04", "role ==", "android-slice-account-01", "AppScaffold.kt", "di/AppContainer.kt") +

            // android-check-08 (1)
            rule("android-check-08", "androidx", "android-slice-playapp-01", "podcast/PlaybackStateTest.kt")
}
