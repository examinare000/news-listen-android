package com.rioikeda.newslisten.architecture

/** 型の分類。 */
enum class TypeClass { POPULATION, PORT, PORT_BACKED, FUNCTION, VALUE, INFRA, PLATFORM }

/** hook の分類。 */
enum class HookKind { WRITE, READ, INTERNAL }

/** hook の値が確定する時点。`SETTER` は公開 callback（public な setter による代入）。 */
enum class HookWiring { CONSTRUCTOR, SETTER }

/** hook の形（引数の個数と suspend の有無）。 */
enum class HookShape { NO_ARG, SUSPEND_NO_ARG, ONE_ARG, SUSPEND_ONE_ARG }

/**
 * port 表の 1 行。interface の全 public 抽象メソッドは、[writes]・[sequenceReads]・[propertyReads] のどれか 1 つに載る
 * （JVM のメソッド名）。ApiClient 系の 4 つの interface は 1 行（port 名 `ApiClient`）にまとめる。
 * [interfaces] は interface の完全修飾名。
 */
data class PortRow(
    val port: String,
    val interfaces: Set<String>,
    val writes: Set<String>,
    val sequenceReads: Set<String>,
    val propertyReads: Set<String>,
)

/** hook 表の 1 行。`ownerField` は `<所有者の型名>#<field 名>`。 */
data class HookRow(
    val ownerField: String,
    val kind: HookKind,
    val shape: HookShape,
    val wiring: HookWiring,
    val reason: String? = null,
)

/** 型の判定規則に当たらない型を足した行。完了報告で「仕様外の追加」として列挙する。`typeName` は完全修飾名。 */
data class ClassifiedTypeRow(val typeName: String, val classification: TypeClass, val reason: String)

/** port 表・hook 表・型の追加行。型の分類そのものは reflection で決め、手書きにしない。 */
data class EffectTypeCatalog(
    val ports: List<PortRow>,
    val hooks: List<HookRow>,
    val addedTypes: List<ClassifiedTypeRow> = emptyList(),
) {
    companion object {
        private const val NETWORK = "com.rioikeda.newslisten.network"

        val production: EffectTypeCatalog = EffectTypeCatalog(
            ports = listOf(
                PortRow(
                    port = "ApiClient",
                    interfaces = setOf("$NETWORK.ApiClient", "$NETWORK.LearningApi", "$NETWORK.VocabularyTestApi"),
                    writes = setOf(
                        "login", "logout", "starArticle", "dismissArticle", "updatePlaybackPosition", "markCompleted",
                        "submitQuizAnswers", "registerDeviceToken", "unregisterDeviceToken", "createSource", "updateSource",
                        "deleteSource", "updatePreferences", "updateWeeklyGoalEpisodes", "saveVocabulary", "deleteVocabulary",
                        "submitVocabularyTestResults", "updateProfile", "changePassword", "revokeSession", "revokeOtherSessions",
                        "reportClientError", "completeOnboarding", "passkeyRegisterOptions", "passkeyRegisterVerify",
                        "passkeyLoginOptions", "passkeyLoginVerify", "deletePasskeyCredential",
                    ),
                    sequenceReads = setOf(
                        "me", "fetchFeed", "fetchPodcasts", "fetchPodcast", "fetchPreferences", "downloadAudio", "fetchSources",
                        "fetchFeaturedSites", "fetchGenerationQuota", "fetchListeningStreak", "fetchLearningDashboard",
                        "fetchVocabulary", "fetchVocabularyTestSession", "listSessions", "fetchOnboardingStatus",
                        "listPasskeyCredentials",
                    ),
                    propertyReads = emptySet(),
                ),
                PortRow(
                    port = "PodcastApi",
                    interfaces = setOf("$NETWORK.PodcastApi"),
                    writes = setOf("updatePlaybackPosition", "markCompleted"),
                    sequenceReads = setOf("fetchPodcasts", "fetchPodcast", "downloadAudio"),
                    propertyReads = emptySet(),
                ),
                PortRow(
                    port = "PreferencesStore",
                    interfaces = setOf("com.rioikeda.newslisten.preferences.PreferencesStore"),
                    writes = setOf(
                        "setDefaultDifficulty", "setDefaultPlaybackSpeed", "setArticleOpenMode", "setTimeFormat",
                        "setSfxEnabled", "setHapticsEnabled", "setWeeklyGoalEpisodes", "markAchievementsSeen",
                    ),
                    sequenceReads = emptySet(),
                    propertyReads = setOf(
                        "getDefaultDifficulty", "getDefaultPlaybackSpeed", "getArticleOpenMode", "getTimeFormat",
                        "getSfxEnabled", "getHapticsEnabled", "getWeeklyGoalEpisodes", "getSeenAchievementIds",
                    ),
                ),
                PortRow(
                    port = "SessionStore",
                    interfaces = setOf("$NETWORK.SessionStore"),
                    writes = setOf("save", "clear"),
                    sequenceReads = setOf("load"),
                    propertyReads = emptySet(),
                ),
                PortRow(
                    port = "PlayerController",
                    interfaces = setOf("com.rioikeda.newslisten.podcast.PlayerController"),
                    writes = setOf("prepare", "play", "pause", "seekTo", "setSpeed", "stop", "release", "setOnPlaybackCompleted"),
                    sequenceReads = emptySet(),
                    propertyReads = setOf(
                        "isPlaying", "getPositionSeconds", "getDurationSeconds", "getPlaybackSpeed", "getState",
                        "getOnPlaybackCompleted",
                    ),
                ),
                PortRow(
                    port = "FileSystem",
                    interfaces = setOf("$NETWORK.FileSystem"),
                    writes = setOf("write", "delete", "ensureDirectory"),
                    sequenceReads = setOf("exists", "listFiles", "fileSize"),
                    propertyReads = emptySet(),
                ),
                PortRow(
                    port = "NetworkMonitoring",
                    interfaces = setOf("$NETWORK.NetworkMonitoring"),
                    writes = setOf("start", "stop"),
                    sequenceReads = emptySet(),
                    propertyReads = setOf("isOnline"),
                ),
                PortRow(
                    port = "PasskeyProvider",
                    interfaces = setOf("com.rioikeda.newslisten.passkey.PasskeyProvider"),
                    writes = setOf("createCredential", "getCredential"),
                    sequenceReads = emptySet(),
                    propertyReads = emptySet(),
                ),
                PortRow(
                    port = "FeedbackPlayer",
                    interfaces = setOf("com.rioikeda.newslisten.designsystem.FeedbackPlayer"),
                    writes = setOf("playSound", "performHaptic"),
                    sequenceReads = emptySet(),
                    propertyReads = emptySet(),
                ),
            ),
            hooks = listOf(
                HookRow("AuthViewModel#onSubjectLeave", HookKind.WRITE, HookShape.SUSPEND_NO_ARG, HookWiring.CONSTRUCTOR),
                HookRow("AuthViewModel#onAuthenticated", HookKind.WRITE, HookShape.SUSPEND_NO_ARG, HookWiring.CONSTRUCTOR),
                HookRow("PasskeyLoginViewModel#onLoginSuccess", HookKind.WRITE, HookShape.SUSPEND_ONE_ARG, HookWiring.CONSTRUCTOR),
                HookRow(
                    "PasskeyRegistrationViewModel#onRegistered", HookKind.WRITE, HookShape.SUSPEND_NO_ARG, HookWiring.CONSTRUCTOR,
                    reason = "規則どおり WRITE（中身は loadCredentials だが保守側に倒す）",
                ),
                HookRow("FeedViewModel#onStarConfirmed", HookKind.WRITE, HookShape.NO_ARG, HookWiring.SETTER),
                HookRow("ApiListeningStreakStore#onStreakIncreased", HookKind.WRITE, HookShape.NO_ARG, HookWiring.SETTER),
                HookRow(
                    "TabItem#screen", HookKind.WRITE, HookShape.NO_ARG, HookWiring.CONSTRUCTOR,
                    reason = "Composable の関数型（JVM では Function2<Composer, Integer, Unit>）。Composable の局所変数だけが持ち、probe のグラフに入らない",
                ),
                HookRow("ApiListeningStreakStore#fetchListeningStreak", HookKind.READ, HookShape.SUSPEND_NO_ARG, HookWiring.CONSTRUCTOR),
                HookRow("SettingsViewModel#isAdminProvider", HookKind.READ, HookShape.NO_ARG, HookWiring.CONSTRUCTOR),
                HookRow("VocabularyTestViewModel#shuffle", HookKind.READ, HookShape.ONE_ARG, HookWiring.CONSTRUCTOR),
                HookRow("FcmTokenRegistrar#tokenProvider", HookKind.READ, HookShape.SUSPEND_NO_ARG, HookWiring.CONSTRUCTOR),
                HookRow("FcmTokenRegistrar#permissionChecker", HookKind.READ, HookShape.NO_ARG, HookWiring.CONSTRUCTOR),
                HookRow("DSFeedback#sfxEnabled", HookKind.READ, HookShape.NO_ARG, HookWiring.CONSTRUCTOR),
                HookRow("DSFeedback#hapticsEnabled", HookKind.READ, HookShape.NO_ARG, HookWiring.CONSTRUCTOR),
                HookRow("DSFeedback#nowMillis", HookKind.READ, HookShape.NO_ARG, HookWiring.CONSTRUCTOR),
            ),
            addedTypes = listOf(
                ClassifiedTypeRow(
                    "$NETWORK.AudioCacheManager", TypeClass.PORT_BACKED,
                    "FileSystem の上の実物（network/AudioCacheManager.kt）。probe は実物を記録 fake の上に組む",
                ),
                ClassifiedTypeRow(
                    "androidx.compose.runtime.Composer", TypeClass.PLATFORM,
                    "Composable の関数型（JVM では Function2<Composer, Integer, Unit>）の合成引数。UI の合成に作用する。TabItem#screen の型にだけ現れる（spec 外の追加）",
                ),
            ),
        )
    }
}
