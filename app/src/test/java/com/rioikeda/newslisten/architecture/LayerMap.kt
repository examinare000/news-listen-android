package com.rioikeda.newslisten.architecture

/**
 * main の全 `.kt` を層へ対応づける表（Spec §3.2・§7 android-check-01）。
 *
 * ファイルごとに明示する。directory の規則にしないのは、新しいファイルを足したときに
 * 「対応表に無いファイル」で落とすため。層の名前の規則と、directory・全ファイルで判定する規則の
 * 範囲は、`ArchitectureStructureTest` が仕様どおりに当てる。
 * `*ViewModel` は presentation に置く（android-dec-09）。
 *
 * 限界:
 * - 同じ package の参照は import に現れないので、依存の向きの検査では見えない。
 * - 型の情報を持たないので、名前の形でしか判定できない検査（port の受け取り・遷移関数の呼出）がある。
 *
 * パスは main の根（`app/src/main/java/com/rioikeda/newslisten/`）からの相対で、`/` 区切り。
 */
enum class Layer {
    DOMAIN,
    APPLICATION,
    PORT,
    READ_MODEL,
    ADAPTER,
    PRESENTATION,
    ENTRY,
    COMPOSITION_ROOT,
    TEST_DOUBLE,
}

/** 型単位の行。ファイルの分類は変えず、その型だけ別の層として扱う。 */
data class TypeRow(val file: String, val type: String, val layer: Layer, val owner: String)

/** 恒久の例外（許可リストと別。使われなくなったら失敗させる）。 */
data class PermanentException(val id: String, val rule: String, val file: String, val target: String)

object LayerMap {

    private val domain = listOf(
        "core/Difficulty.kt",
        "core/PlaybackQueue.kt",
        "core/PlaybackSourceResolver.kt",
        "core/RelativeTimeFormatter.kt",
        "core/ResumeRule.kt",
        "podcast/PlaybackSession.kt",
        "podcast/PlaybackState.kt",
        "podcast/PlaybackConstants.kt",
        "auth/AuthState.kt",
        "preferences/ArticleOpenMode.kt",
        "preferences/TimeFormat.kt",
        "feed/ArticleUrlValidator.kt",
    )

    private val application = listOf(
        "feed/BulkActionResult.kt",
        "feed/PendingArticleAction.kt",
        "engagement/ListeningStreakStore.kt",
        "notification/FcmTokenRegistrar.kt",
    )

    private val port = listOf(
        "podcast/PlayerController.kt",
        "passkey/PasskeyProvider.kt",
        "preferences/PreferencesStore.kt",
        "network/PodcastApi.kt",
        "network/LearningApi.kt",
        "network/VocabularyTestApi.kt",
        "network/SessionStore.kt",
        "network/NetworkMonitoring.kt",
    )

    private val readModel = listOf(
        "podcast/PlaybackMetadata.kt",
    )

    private val adapter = listOf(
        "podcast/ExoPlayerController.kt",
        "passkey/CredentialManagerPasskeyProvider.kt",
        "preferences/DataStorePreferencesStore.kt",
        "network/ApiClient.kt",
        "network/ApiEndpoint.kt",
        "network/ApiException.kt",
        "network/AudioCacheManager.kt",
        "network/AuthInterceptor.kt",
        "network/ConnectivityNetworkMonitor.kt",
        "network/EncryptedTokenEnvelope.kt",
        "network/FileSystem.kt",
        "network/JavaFileSystem.kt",
        "network/KeystoreSessionStore.kt",
        "network/OkHttpApiClient.kt",
        "observability/CrashReporter.kt",
        "observability/CrashReportFormatter.kt",
        "model/ActionResponse.kt",
        "model/ArticleResponse.kt",
        "model/ClientErrorReport.kt",
        "model/DeviceTokenRequest.kt",
        "model/FeaturedCategory.kt",
        "model/FeaturedSite.kt",
        "model/FeedResponse.kt",
        "model/GenerationQuotaResponse.kt",
        "model/LearningEngagementModels.kt",
        "model/ListeningStreakResponse.kt",
        "model/LoginRequest.kt",
        "model/LoginResponse.kt",
        "model/NewsListenJson.kt",
        "model/OnboardingStatusResponse.kt",
        "model/PasskeyCredential.kt",
        "model/PasskeyCredentialsListResponse.kt",
        "model/PasskeyLoginOptionsRequest.kt",
        "model/PasskeyOptionsResponse.kt",
        "model/PasskeyVerifyRequest.kt",
        "model/PasswordChangeRequest.kt",
        "model/PlaybackPositionRequest.kt",
        "model/PodcastListResponse.kt",
        "model/PodcastResponse.kt",
        "model/PreferencesResponse.kt",
        "model/ProfileUpdateRequest.kt",
        "model/QuizAnswerRequest.kt",
        "model/QuizAnswerResponse.kt",
        "model/QuizQuestion.kt",
        "model/RevokeSessionsResponse.kt",
        "model/RssSource.kt",
        "model/RssSourceRequest.kt",
        "model/SessionResponse.kt",
        "model/SessionsListResponse.kt",
        "model/StarRequest.kt",
        "model/TranscriptSegment.kt",
        "model/UpdatePreferencesRequest.kt",
        "model/UserResponse.kt",
        "model/VocabularyEntry.kt",
        "model/VocabularyModels.kt",
    )

    private val presentation = listOf(
        "AppScaffold.kt",
        "auth/LoginScreen.kt",
        "designsystem/Color.kt",
        "designsystem/DSFeedback.kt",
        "designsystem/RelevanceBar.kt",
        "designsystem/Spacing.kt",
        "designsystem/Theme.kt",
        "designsystem/Type.kt",
        "feed/FeedScreen.kt",
        "feed/ArticleOpener.kt",
        "feed/ArticleDateFormatter.kt",
        "learning/LearningScreen.kt",
        "learning/AchievementCatalog.kt",
        "onboarding/OnboardingScreen.kt",
        "podcast/AudioPlayerSection.kt",
        "podcast/PodcastRowView.kt",
        "podcast/PodcastScreen.kt",
        "podcast/PodcastStatusBadge.kt",
        "podcast/QueueSheet.kt",
        "podcast/QuizSheet.kt",
        "settings/SettingsScreen.kt",
        "vocabulary/VocabularyTestScreen.kt",
        "podcast/PodcastViewModel.kt",
        "auth/AuthViewModel.kt",
        "account/AccountViewModel.kt",
        "account/SessionsViewModel.kt",
        "passkey/PasskeyCredentialsViewModel.kt",
        "passkey/PasskeyLoginViewModel.kt",
        "passkey/PasskeyRegistrationViewModel.kt",
        "feed/FeedViewModel.kt",
        "learning/LearningViewModel.kt",
        "vocabulary/VocabularyTestViewModel.kt",
        "settings/SettingsViewModel.kt",
        "onboarding/OnboardingViewModel.kt",
    )

    private val entry = listOf(
        "MainActivity.kt",
        "NewsListenApplication.kt",
        "playbackservice/PlaybackService.kt",
        "notification/FcmTokenService.kt",
    )

    private val compositionRoot = listOf(
        "di/AppContainer.kt",
    )

    private val testDouble = listOf(
        "preferences/InMemoryPreferencesStore.kt",
        "network/InMemorySessionStore.kt",
    )

    /** 行の列。key の重複を検査するため、map にせず list で持つ（CI-02）。 */
    val rows: List<Pair<String, Layer>> =
        domain.map { it to Layer.DOMAIN } +
            application.map { it to Layer.APPLICATION } +
            port.map { it to Layer.PORT } +
            readModel.map { it to Layer.READ_MODEL } +
            adapter.map { it to Layer.ADAPTER } +
            presentation.map { it to Layer.PRESENTATION } +
            entry.map { it to Layer.ENTRY } +
            compositionRoot.map { it to Layer.COMPOSITION_ROOT } +
            testDouble.map { it to Layer.TEST_DOUBLE }

    val byFile: Map<String, Layer> = rows.toMap()

    /** `NowPlaying` は android-slice-playback-03 が `PlaybackSession.kt` に足す。宣言が無い間は検査を skip する（CI-13）。 */
    val typeRows: List<TypeRow> = listOf(
        TypeRow("podcast/PlaybackSession.kt", "NowPlaying", Layer.READ_MODEL, "android-slice-playback-03"),
    )

    val permanentExceptions: List<PermanentException> = listOf(
        // RF17: foreground service を起動するため entry の PlaybackService を import する（Spec §4 android-dep-05）。
        PermanentException("PE-1", "android-dep-05", "podcast/ExoPlayerController.kt", "layer:ENTRY"),
        // PE-3 の生成に要る CredentialManagerPasskeyProvider の import（MainActivity.kt:35）。
        PermanentException("PE-2", "android-dep-04", "MainActivity.kt", "layer:ADAPTER"),
        // Activity の Context が要るので MainActivity で生成する（Spec §4 android-dep-06）。
        PermanentException("PE-3", "android-dep-06", "MainActivity.kt", "CredentialManagerPasskeyProvider"),
    )
}
