package com.rioikeda.newslisten.di

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.rioikeda.newslisten.BuildConfig
import com.rioikeda.newslisten.account.ui.AccountViewModel
import com.rioikeda.newslisten.account.ui.SessionsViewModel
import com.rioikeda.newslisten.account.app.AccountApi
import com.rioikeda.newslisten.account.app.AccountCommands
import com.rioikeda.newslisten.account.app.SessionQueries
import com.rioikeda.newslisten.auth.domain.AuthState
import com.rioikeda.newslisten.auth.ui.AuthViewModel
import com.rioikeda.newslisten.auth.app.AuthSession
import com.rioikeda.newslisten.auth.domain.CleanupStep
import com.rioikeda.newslisten.auth.app.SubjectCleanup
import com.rioikeda.newslisten.catalog.app.EpisodeCatalog
import com.rioikeda.newslisten.feed.ui.FeedViewModel
import com.rioikeda.newslisten.feed.app.FeedApi
import com.rioikeda.newslisten.feed.app.FeedCommands
import com.rioikeda.newslisten.feed.app.FeedQueries
import com.rioikeda.newslisten.feed.app.FeedState
import com.rioikeda.newslisten.learning.ui.LearningViewModel
import com.rioikeda.newslisten.learning.app.LearningCommands
import com.rioikeda.newslisten.learning.app.LearningDashboardQuery
import com.rioikeda.newslisten.learning.app.StreakQuery
import com.rioikeda.newslisten.learning.app.VocabularyTestCommands
import com.rioikeda.newslisten.network.AccountApiAdapter
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.learning.ui.VocabularyTestViewModel
import com.rioikeda.newslisten.network.AudioCacheManager
import com.rioikeda.newslisten.network.AuthApiAdapter
import com.rioikeda.newslisten.network.AuthInterceptor
import com.rioikeda.newslisten.network.DeviceTokenApiAdapter
import com.rioikeda.newslisten.network.ConnectivityNetworkMonitor
import com.rioikeda.newslisten.network.JavaFileSystem
import com.rioikeda.newslisten.network.FeedApiAdapter
import com.rioikeda.newslisten.network.LearningApiAdapter
import com.rioikeda.newslisten.network.VocabularyTestApiAdapter
import com.rioikeda.newslisten.network.KeystoreSessionStore
import com.rioikeda.newslisten.network.OkHttpApiClient
import com.rioikeda.newslisten.network.PodcastApiAdapter
import com.rioikeda.newslisten.network.PreferencesApiAdapter
import com.rioikeda.newslisten.auth.app.SessionStore
import com.rioikeda.newslisten.network.SourcesApiAdapter
import com.rioikeda.newslisten.notification.app.FcmTokenRegistrar
import com.rioikeda.newslisten.observability.CrashReporter
import com.rioikeda.newslisten.observability.DeviceInfo
import com.rioikeda.newslisten.onboarding.ui.OnboardingViewModel
import com.rioikeda.newslisten.passkey.ui.PasskeyCredentialsViewModel
import com.rioikeda.newslisten.passkey.ui.PasskeyLoginViewModel
import com.rioikeda.newslisten.passkey.app.PasskeyProvider
import com.rioikeda.newslisten.passkey.ui.PasskeyRegistrationViewModel
import com.rioikeda.newslisten.passkey.app.PasskeyCommands
import com.rioikeda.newslisten.podcast.infra.ExoPlayerController
import com.rioikeda.newslisten.podcast.app.OfflineLibrary
import com.rioikeda.newslisten.podcast.ui.PodcastViewModel
import com.rioikeda.newslisten.podcast.app.PlaybackCoordinator
import com.rioikeda.newslisten.podcast.app.PositionCommands
import com.rioikeda.newslisten.podcast.app.PositionQueries
import com.rioikeda.newslisten.podcast.app.PositionStore
import com.rioikeda.newslisten.podcast.infra.DataStorePositionStore
import com.rioikeda.newslisten.preferences.infra.DataStorePreferencesStore
import com.rioikeda.newslisten.preferences.app.PreferencesStore
import com.rioikeda.newslisten.preferences.app.PreferencesSync
import com.rioikeda.newslisten.notification.infra.AndroidNotificationPermission
import com.rioikeda.newslisten.notification.infra.FirebasePushTokenSource
import com.rioikeda.newslisten.onboarding.app.OnboardingCommands
import com.rioikeda.newslisten.settings.ui.SettingsViewModel
import com.rioikeda.newslisten.settings.app.SourcesCommands
import com.rioikeda.newslisten.settings.app.SourcesQueries
import com.rioikeda.newslisten.settings.app.SourcesState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.time.Clock
import java.util.concurrent.TimeUnit

/**
 * アプリケーション スコープ依存グラフの管理。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Networking/AppState.swift:20-70（AppState init）のミラー。
 * iOS は @StateObject で初期化遅延させるが、Android は Application.onCreate で即座に
 * 依存グラフを構築し、MainActivity から取得する設計。
 *
 * Hilt 不採用理由（ADR-066）: 10 前後の ViewModel のみで、フレームワークの自動生成コストが
 * 正当化されない。手書きコンストラクタ注入で十分。
 */
class AppContainer(context: Context) {
    private val appContext: Context = context.applicationContext
    private val baseUrl: String = BuildConfig.API_BASE_URL
    private val apiKey: String = BuildConfig.API_KEY

    // Session Store: セッショントークンの永続化（Keystore 暗号化）
    private val sessionStore: SessionStore = KeystoreSessionStore(context)

    // Token Provider: AuthInterceptor が都度トークンを取得する関数
    private val tokenProvider: () -> String? = { sessionStore.load() }

    // HTTP Client: タイムアウト + AuthInterceptor 設定
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        // connect timeout: iOS URLSession 既定値 30s に統一。ネットワーク接続確立時間の上限。
        .connectTimeout(30, TimeUnit.SECONDS)
        // read timeout: iOS URLSession 既定値 60s に統一。リクエスト送信後、レスポンス受信待ちの上限。
        .readTimeout(60, TimeUnit.SECONDS)
        // write timeout: iOS URLSession 既定値 60s に統一。リクエストボディ送信の上限（大容量アップロード対応）。
        .writeTimeout(60, TimeUnit.SECONDS)
        // 認証情報付与 Interceptor
        .addInterceptor(
            AuthInterceptor(
                apiBaseUrl = baseUrl.toHttpUrl(),
                apiKey = apiKey,
                // OkHttp のコールバックスレッドから同期的に呼ぶ（android-slice-auth-01・android-contract-13・F5）。
                // `authSession` は `by lazy`（synchronized）で、ラムダは呼ばれた時点で解決する
                // ため、ここで参照しても循環初期化にはならない。
                onUnauthorized = { attachedToken -> authSession.onUnauthorized(attachedToken) },
                tokenProvider = tokenProvider
            )
        )
        .build()

    // API Client: OkHttp + DTO シリアライズ
    private val apiClient: ApiClient = OkHttpApiClient(
        baseUrl = baseUrl.toHttpUrl(),
        okHttpClient = okHttpClient
    )

    /**
     * 音声キャッシュマネージャ（フェーズ8-B・ADR-027）。
     *
     * 正本: ios/NewsListenApp/NewsListenApp/Networking/AudioCacheManager.swift（iOS は
     * PodcastViewModel.swift:87 のデフォルト引数で `FileManager.default` の `Caches/` を暗黙に使う）。
     * Android は Hilt 不採用方針（ADR-066）に合わせ、AppContainer で明示的に組み立てて注入する。
     * `Context.cacheDir` はアプリのアンインストール/OS のストレージ逼迫時に自動削除され得る領域で、
     * オフライン再生用の一時キャッシュとして妥当（iOS の `Caches/` ディレクトリと同じ位置付け）。
     */
    private val audioCacheManager: AudioCacheManager = AudioCacheManager(
        fileSystem = JavaFileSystem(),
        baseDir = appContext.cacheDir.absolutePath
    )

    /**
     * ネットワーク接続状態の監視（フェーズ8-C・shared-playback-spec.md §6.1）。
     * PlaybackCoordinator の resolvePlaybackSource 判定に isOnline を供給する。
     */
    private val networkMonitor: ConnectivityNetworkMonitor = ConnectivityNetworkMonitor(appContext).apply { start() }

    /**
     * 音声キャッシュの主体固定・起動時回収・ダウンロードの cancel を所有する capsule（android-dec-02）。
     * `authSession` のラムダがこの値を読むので `by lazy` にする。`authSession` は構築時にこの値を評価しないため、
     * 初期化は `offlineLibrary` → `authSession` の一方向で、循環しない。
     */
    private val offlineLibrary: OfflineLibrary by lazy {
        OfflineLibrary(
            audioStore = audioCacheManager,
            podcastApi = PodcastApiAdapter(apiClient),
            episodeLookup = _episodeCatalog,
            currentSubject = authSession::currentSubjectId,
            dispatcher = Dispatchers.Default.limitedParallelism(1),
        )
    }

    /**
     * 位置の記録の端末保存。[positionCommands]（書く）と [positionQueries]（読む）が同じ instance を共有する
     * （別々に作ると、記録が書く先と再開位置が読む先が別になる）。
     */
    private val playbackPositionStore: PositionStore by lazy {
        DataStorePositionStore(
            PreferenceDataStoreFactory.create(
                scope = preferencesScope,
                produceFile = { File(appContext.filesDir, "playback_positions.preferences_pb") },
            ),
        )
    }

    /** 位置の記録・保存・送信・再送・離脱での削除。`authSession` を読むので `by lazy`。 */
    private val positionCommands: PositionCommands by lazy {
        PositionCommands(
            PodcastApiAdapter(apiClient),
            playbackPositionStore,
            Clock.systemUTC(),
            authSession::currentSubjectId,
            Dispatchers.Default.limitedParallelism(1),
        )
    }

    private val positionQueries: PositionQueries by lazy { PositionQueries(playbackPositionStore) }

    /**
     * FCM デバイストークンの登録ロジック（フェーズ9）。port の実体（Firebase・通知権限・登録 API）は
     * adapter が持ち、ここは組み立てるだけ。adapter は式の中で作る（by lazy でない val は宣言順に評価される）。
     */
    private val fcmTokenRegistrar: FcmTokenRegistrar = FcmTokenRegistrar(
        deviceTokenApi = DeviceTokenApiAdapter(apiClient),
        pushTokenSource = FirebasePushTokenSource(),
        notificationPermission = AndroidNotificationPermission(appContext),
        dispatcher = Dispatchers.Default,
    )

    fun getFcmTokenRegistrar(): FcmTokenRegistrar = fcmTokenRegistrar

    /**
     * クラッシュレポータ（フェーズ12・issue #140）。
     *
     * 正本: ios/NewsListenApp/NewsListenApp/Observability/CrashReporter.swift のミラー。
     * crashFile は filesDir 配下に置く（cacheDir と異なりストレージ逼迫時に OS が削除し得ない、
     * 永続化すべきクラッシュレポートの保存先として妥当）。
     */
    private val crashReporter: CrashReporter = CrashReporter(
        crashFile = File(appContext.filesDir, "crash_report.json"),
        deviceInfo = DeviceInfo(appVersion = BuildConfig.VERSION_NAME, osVersion = Build.VERSION.RELEASE),
        apiClient = apiClient,
        dispatcher = Dispatchers.Default,
    )

    fun getCrashReporter(): CrashReporter = crashReporter

    /**
     * CrashReporter.flush（起動時の一度きりの送信）用の長寿命 CoroutineScope（フェーズ12・issue #140）。
     *
     * WHY SupervisorJob + Dispatchers.Default: preferencesScope と同じ理由で、
     * Application.onCreate 内で都度 `CoroutineScope(Dispatchers.Default)` を生成する実装だと
     * 他の長寿命スコープ（preferencesScope 等）と管理方針が揃わない。AppContainer が
     * 依存グラフとスコープの両方を一元管理する既存パターンに合わせ、ここに集約する。
     * flush 失敗がアプリの他の処理に伝播しないよう SupervisorJob を使う。
     */
    private val crashReporterScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 永続化済みクラッシュレポートの送信を非同期に開始する（Application.onCreate から一度だけ呼ぶ）。 */
    fun flushCrashReporter() {
        crashReporterScope.launch { crashReporter.flush() }
    }

    /**
     * PreferencesStore（フェーズ10 P10 Task3）用の長寿命 CoroutineScope。
     *
     * WHY SupervisorJob + Dispatchers.Default: AudioCacheManager 等と異なり、値の読み取り
     * （StateFlow）がアプリ全体の寿命にわたって必要なため、特定の ViewModel のスコープではなく
     * AppContainer（Application スコープ）に紐づく専用スコープを持つ。SupervisorJob により
     * 個々の書き込み失敗が他の購読を巻き込んで停止させない。
     */
    private val preferencesScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * ユーザー設定選択（難易度・再生速度・記事の開き方・日付表記）の値所有層（フェーズ10 P10 Task3）。
     *
     * 正本: ios/NewsListenApp/NewsListenApp/AppState.swift（UserDefaults 永続化）のミラー。
     * ファイルは `filesDir` 配下に置く（`cacheDir` はストレージ逼迫時に OS が削除し得るため、
     * 恒久的なユーザー設定の保存先として不適切）。
     */
    private val preferencesStore: PreferencesStore = DataStorePreferencesStore(
        dataStore = PreferenceDataStoreFactory.create(
            scope = preferencesScope,
            produceFile = { File(appContext.filesDir, "user_preferences.preferences_pb") },
        ),
        scope = preferencesScope,
    )

    /**
     * 設定の同期の入口。`authSession` のラムダと後始末の手順がこの値を読むので `by lazy` にする。
     * `authSession` は構築時にこの値を評価しないため、初期化は `preferencesSync` → `authSession` の一方向で、循環しない。
     */
    private val preferencesSync: PreferencesSync by lazy {
        PreferencesSync(
            PreferencesApiAdapter(apiClient),
            preferencesStore,
            authSession::currentSubjectId,
            preferencesScope,
        )
    }

    private val learningApi = LearningApiAdapter(apiClient)
    private val vocabularyTestApi = VocabularyTestApiAdapter(apiClient)
    private val feedApi: FeedApi = FeedApiAdapter(apiClient)
    private val sourcesApi = SourcesApiAdapter(apiClient)

    /** 聴取ストリークの読み。AppScaffold・設定画面・再生が同じものを共有する。 */
    private val _streakQuery: StreakQuery = StreakQuery(
        api = learningApi,
        dispatcher = Dispatchers.Default.limitedParallelism(1),
    )

    fun getStreakQuery(): StreakQuery = _streakQuery

    /**
     * 主体離脱の後始末の手順列（ADR-104）。呼出元の cancel で止まらないよう、`SupervisorJob` の長寿命 scope で動かす。
     * 手順は実行時にラムダの中で各資産を読むので、構築時に `_podcastViewModel` などを評価しない。
     */
    private val subjectCleanup: SubjectCleanup by lazy {
        SubjectCleanup(
            steps = listOf(
                CleanupStep("playback_stop") { _podcastViewModel.stopForSubjectLeave() },
                CleanupStep("download_cancel") { s -> s.userId?.let { offlineLibrary.cancelDownloads(it) } },
                CleanupStep("audio_cache") { s -> s.userId?.let { offlineLibrary.removeSubject(it) } },
                CleanupStep("position_records") { s -> s.userId?.let { positionCommands.discardSubject(it) } },
                CleanupStep("preferences") { preferencesSync.clearSubjectScoped() },
                CleanupStep("fcm_local_reset") { fcmTokenRegistrar.onLogout() },
            ),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    /**
     * AuthSession（認証状態の持ち主）を生成して返す。
     *
     * Dispatcher: Dispatchers.Default は CPU バウンドタスク向き。IO タスクには Dispatchers.IO を
     * 推奨するが、login/refreshAuth は mainly ネットワーク IO であり、OkHttpClient が内部で
     * スレッドプールを持つため、Default でも問題ない（スレッド枯渇リスクは低い）。テスト時に
     * StandardTestDispatcher 差し替えで同期化テストを可能にする設計。
     *
     * by lazy でシングルトンキャッシュ化：画面回転時に AuthSession インスタンスが同じ
     * ままであることを保証し、authState が Unknown にリセットされるのを防ぐ。
     *
     * onSubjectLeave（フェーズ8-D・shared-playback-spec.md §6.3・ADR-104）: 主体が離れたとき、離脱主体を引数に
     * [subjectCleanup] を起動する（完了は待たない）。手順列は [subjectCleanup] が持ち、auth 層に各資産の実体を
     * 直接依存させない。音声キャッシュは離れる主体 1 人分だけを対象にする（端末全体を消さない。ADR-104 決定 2）。
     */
    private val authSession: AuthSession by lazy {
        AuthSession(
            authApi = AuthApiAdapter(apiClient),
            sessionStore = sessionStore,
            dispatcher = Dispatchers.Default,
            onSubjectLeave = subjectCleanup::start,
            onSessionRestored = { preferencesSync.syncFromServer() },
            onAuthenticated = { fcmTokenRegistrar.onAuthenticated() },
            // Unknown からの確定（shared-dec-05）と遷移 ④ の B の確立（android-dec-48）の直後に回収する。形式の判断は Subject の生成に任せる。
            onSubjectResolved = { s -> offlineLibrary.reclaim(s?.userId); positionCommands.onSubjectResolved(s?.userId) },
        )
    }

    /**
     * AuthViewModel（ログイン画面の入力の可否・文言）を生成して返す。by lazy でシングルトンにし、
     * 画面回転時に `loginErrorMessage` が失われないようにする。認証状態は [authSession] が持つ。
     */
    private val _authViewModel: AuthViewModel by lazy {
        AuthViewModel(authSession = authSession)
    }

    fun getAuthViewModel(): AuthViewModel = _authViewModel

    /**
     * アカウント・セッション・パスキーの往復の port。入口（[accountCommands]・[sessionQueries]・パスキーの手順）が共有する。
     * アダプタは状態を持たないので 1 つでよく、`by lazy` で [apiClient] の初期化の後に作る。
     */
    private val accountApi: AccountApi by lazy { AccountApiAdapter(apiClient) }

    /** 表示名・パスワード・セッション失効・パスキー削除の書込の入口。表示名の反映先は [authSession]。 */
    private val accountCommands: AccountCommands by lazy { AccountCommands(accountApi, authSession) }

    /** セッション・パスキー一覧の読みの入口。状態は持たない。 */
    private val sessionQueries: SessionQueries by lazy { SessionQueries(accountApi) }

    /**
     * Feed の共有の状態。[FeedCommands] と [FeedQueries] が同じ 1 つを受ける（別々だと、画面が読む一覧と
     * Star が除く一覧が別になり、画面に何も起きない）。
     *
     * Dispatcher: Dispatchers.Default.limitedParallelism(1) で状態変更を直列化する。
     * WHY: 保留の読み取り→null 代入→commit 送信と、stage の一覧の読み取り→変更→書き込みが別スレッドで
     * 競合すると、二重送信や記事消失が起こり得る。iOS の @MainActor による直列実行を Kotlin で再現するため、
     * limitedParallelism(1) で全操作を単一スレッドで順序付ける。
     */
    private val feedState: FeedState by lazy { FeedState(Dispatchers.Default.limitedParallelism(1)) }

    /**
     * FeedViewModel（フィード一覧・Star/Dismiss・Undo）を生成して返す。
     *
     * by lazy でシングルトンキャッシュ化：画面回転時に FeedViewModel インスタンスが同じ
     * ままであることを保証し、フィード一覧の状態が保持される。
     *
     * 要件3: Star の確定の事象は feedbackEvents に出る。FeedScreen がそれを集めて音を鳴らす。
     */
    private val _feedViewModel: FeedViewModel by lazy {
        FeedViewModel(
            commands = FeedCommands(feedApi, feedState),
            queries = FeedQueries(feedApi, feedState),
            preferences = preferencesSync.preferences,
        )
    }

    fun getFeedViewModel(): FeedViewModel = _feedViewModel

    /**
     * ExoPlayerController（Media3 ExoPlayer 実装）を生成して返す。
     *
     * by lazy でシングルトンキャッシュ化：画面回転時に ExoPlayerController インスタンスが
     * 同じままであることを保証し、再生状態の中断を防ぐ。
     *
     * WHY: ExoPlayer インスタンスは内部で native リソース（デコーダ等）を持つため、
     * 不必要な再生成を避ける。Context は Application スコープを使用してメモリリーク回避。
     */
    private val _playerController: ExoPlayerController by lazy {
        ExoPlayerController(appContext)
    }

    fun getPlayerController(): ExoPlayerController = _playerController

    /**
     * 再生の状態変更を直列化する dispatcher。再生の use case と一覧の状態の書込が 1 つの直列化の範囲に入るよう、
     * Coordinator と PodcastViewModel に同じ値を渡す（limitedParallelism は呼ぶたびに別の view を作る）。
     */
    private val podcastDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /**
     * Podcast 一覧の取得を持つ Catalog。Coordinator・OfflineLibrary（id の解決）と PodcastViewModel（一覧）が同じ 1 つを共有する。
     * 取得の後の保存済み集合の再計算は、束縛参照にせず lambda で包む: 束縛参照は構築時に offlineLibrary を評価し、lazy が循環する。
     */
    private val _episodeCatalog: EpisodeCatalog by lazy {
        EpisodeCatalog(
            podcastApi = PodcastApiAdapter(apiClient),
            dispatcher = podcastDispatcher,
            afterRefresh = { offlineLibrary.syncDownloadedState(it); positionCommands.adoptEpisodes(authSession.currentSubjectId(), it) },
        )
    }

    /**
     * 再生の use case を持つ Coordinator。PodcastViewModel はこれへ中継する。
     * `authSession` を読むので `by lazy` にし、構築時に `authSession` を評価しない。
     */
    private val _playbackCoordinator: PlaybackCoordinator by lazy {
        PlaybackCoordinator(
            podcastApi = PodcastApiAdapter(apiClient),
            playerController = _playerController,
            offlineLibrary = offlineLibrary,
            networkMonitor = networkMonitor,
            preferencesStore = preferencesStore,
            streakQuery = _streakQuery,
            dispatcher = podcastDispatcher,
            currentSubject = authSession::currentSubjectId,
            episodeLookup = _episodeCatalog,
            positionCommands = positionCommands,
            positionQueries = positionQueries,
        )
    }

    /**
     * PodcastViewModel（Podcast タブの画面の状態と中継）を生成して返す。
     *
     * Dispatcher: Dispatchers.Default.limitedParallelism(1) を使用し、状態変更を直列化する。
     * WHY: 一覧の取得（PodcastViewModel）と再生の開始（PlaybackCoordinator）が複数スレッドで
     * 競合すると、読み取り→書き込みの同期ずれが生じ得る。同じ dispatcher を渡して 1 つの直列化の範囲に入れる。
     * iOS の @MainActor による直列実行を Kotlin で再現する。
     *
     * by lazy でシングルトンキャッシュ化：画面回転時に PodcastViewModel インスタンスが
     * 同じままであることを保証し、fetchPodcasts() の loadingState や nowPlaying が
     * リセットされるのを防ぐ。
     */
    private val _podcastViewModel: PodcastViewModel by lazy {
        PodcastViewModel(
            learningCommands = _learningCommands,
            coordinator = _playbackCoordinator,
            catalog = _episodeCatalog,
            offlineLibrary = offlineLibrary,
            dispatcher = podcastDispatcher,
        )
    }

    fun getPodcastViewModel(): PodcastViewModel = _podcastViewModel

    /**
     * 学習の入口。`PodcastViewModel`・`LearningViewModel`・画面が同じ instance を共有する。
     * 語彙の key の読み書きは `PodcastViewModel` と同じ [podcastDispatcher] の上で直列化する。
     */
    private val _learningCommands: LearningCommands by lazy {
        LearningCommands(api = learningApi, preferencesSync = preferencesSync, dispatcher = podcastDispatcher)
    }

    fun getLearningCommands(): LearningCommands = _learningCommands

    /**
     * LearningViewModel（学習タブ: ダッシュボード・実績・登録語彙）を生成して返す。
     *
     * Dispatcher: 他の ViewModel と同じ理由で Dispatchers.Default.limitedParallelism(1) を使う。
     * dashboard/vocabulary の読み取り→書き込みが複数スレッドで競合すると状態の取りこぼしが
     * 起こり得るため、単一スレッドで直列化する。
     *
     * by lazy でシングルトンキャッシュ化：画面回転時に LearningViewModel インスタンスが
     * 同じままであることを保証し、dashboard/vocabulary の読み込み済み状態を保持する。
     */
    private val _learningViewModel: LearningViewModel by lazy {
        LearningViewModel(
            query = LearningDashboardQuery(
                api = learningApi,
                preferencesStore = preferencesStore,
                currentSubject = authSession::currentSubjectId,
                dispatcher = Dispatchers.Default.limitedParallelism(1),
            ),
            commands = _learningCommands,
        )
    }

    fun getLearningViewModel(): LearningViewModel = _learningViewModel

    /**
     * VocabularyTestViewModel（単語テスト: セッション取得・自己評価・再テスト・結果送信）を
     * 毎回新しいインスタンスで生成して返す。
     *
     * WHY シングルトンにしない: 各テスト実施時に新しいセッションを開始する必要があるため、
     * 毎回新規インスタンスを生成する。テスト結果送信後は新しいインスタンスで次のテストを開始する。
     */
    fun createVocabularyTestViewModel(): VocabularyTestViewModel {
        return VocabularyTestViewModel(
            VocabularyTestCommands(
                api = vocabularyTestApi,
                dispatcher = Dispatchers.Default.limitedParallelism(1),
            ),
        )
    }

    /**
     * Sources の共有の状態。[sourcesCommands] と [sourcesQueries] が同じ 1 つを受ける（別々だと、`addSource` が
     * 書く一覧と画面が読む一覧が別になり、画面に何も起きない）。
     *
     * Dispatcher: Dispatchers.Default.limitedParallelism(1) で一覧の読み取り→書き込みを直列化する。
     */
    private val sourcesState: SourcesState by lazy { SourcesState(Dispatchers.Default.limitedParallelism(1)) }

    /**
     * isAdminProvider: RSS ソース編集（updateSource）は admin 限定（issue #66・ADR-047）。
     * role は認証確立後に非同期で確定するため、構築時の固定値ではなく呼出の時点で
     * authSession.authState.value を評価する。authSession は呼ばれた時点で解決するので循環しない。
     */
    private val sourcesCommands: SourcesCommands by lazy {
        SourcesCommands(
            sourcesApi,
            sourcesState,
            isAdminProvider = {
                (authSession.authState.value as? AuthState.Authenticated)?.user?.role?.isAdmin == true
            },
        )
    }

    private val sourcesQueries: SourcesQueries by lazy { SourcesQueries(sourcesApi, sourcesState) }

    private val onboardingCommands: OnboardingCommands by lazy {
        OnboardingCommands(sourcesApi, Dispatchers.Default.limitedParallelism(1))
    }

    /**
     * SettingsViewModel（設定タブ: RSS ソース管理・おすすめサイト・生成クォータ・聴取ストリーク・
     * 難易度/再生速度のサーバー同期）を生成して返す（フェーズ10 P10 Task2）。
     *
     * Dispatcher: feedState・PodcastViewModel と同じ理由で
     * Dispatchers.Default.limitedParallelism(1) を使う。画面の状態（isLoading・errorMessage・
     * featuredSites・generationQuota とそのフラグ）の書込みが競合しないよう直列化する
     * （sources 一覧の直列化は [sourcesState] の dispatcher が持つ）。
     *
     * Sources の入口（[sourcesCommands]・[sourcesQueries]）は ViewModel の外で作り、ここへ渡す。
     *
     * by lazy でシングルトンキャッシュ化：画面回転時に SettingsViewModel インスタンスが
     * 同じままであることを保証し、generationQuota 等の読み込み済み状態を保持する。
     */
    private val _settingsViewModel: SettingsViewModel by lazy {
        SettingsViewModel(
            sourcesCommands = sourcesCommands,
            sourcesQueries = sourcesQueries,
            preferencesSync = preferencesSync,
            dispatcher = Dispatchers.Default.limitedParallelism(1),
            streakQuery = _streakQuery,
        )
    }

    fun getSettingsViewModel(): SettingsViewModel = _settingsViewModel

    /**
     * AccountViewModel（設定タブ「アカウント」セクション: 表示名更新・パスワード変更）を
     * 生成して返す（フェーズ11 P11 T3）。
     *
     * Dispatcher: 他の ViewModel と同じ理由で Dispatchers.Default.limitedParallelism(1) を使う。
     * 表示名・パスワード入力欄の読み取り→書き込みが複数スレッドで競合すると入力の取りこぼしが
     * 起こり得るため、単一スレッドで直列化する。
     *
     * authViewModel: 入力欄のプリフィルで AuthState を読むために直接注入する
     * （詳細は [AccountViewModel] のクラスコメント参照）。表示名更新の反映は [accountCommands] が行う。
     *
     * by lazy でシングルトンキャッシュ化：画面回転時に AccountViewModel インスタンスが
     * 同じままであることを保証し、入力中の表示名・パスワードやメッセージ表示が保持される。
     */
    private val _accountViewModel: AccountViewModel by lazy {
        AccountViewModel(
            accountCommands = accountCommands,
            authViewModel = _authViewModel,
            dispatcher = Dispatchers.Default.limitedParallelism(1),
        )
    }

    fun getAccountViewModel(): AccountViewModel = _accountViewModel

    /**
     * SessionsViewModel（ログイン中デバイス一覧・個別/一括失効）を生成して返す
     * （フェーズ11 P11 T4・issue #84 相当）。
     *
     * Dispatcher: feedState・SettingsViewModel と同じ理由で
     * Dispatchers.Default.limitedParallelism(1) を使う。sessions リストの読み取り→書き込みが
     * 複数スレッドで競合すると一覧の取りこぼしが起こり得るため、単一スレッドで直列化する。
     *
     * by lazy でシングルトンキャッシュ化：画面回転時に SessionsViewModel インスタンスが
     * 同じままであることを保証し、sessions/revokedOthersCount 等の読み込み済み状態を保持する。
     */
    private val _sessionsViewModel: SessionsViewModel by lazy {
        SessionsViewModel(
            sessionQueries = sessionQueries,
            accountCommands = accountCommands,
            dispatcher = Dispatchers.Default.limitedParallelism(1),
        )
    }

    fun getSessionsViewModel(): SessionsViewModel = _sessionsViewModel

    /**
     * OnboardingViewModel（初回オンボーディング「おすすめサイト追加」ステップ）を生成して返す
     * （フェーズ13・issue #140 P13）。
     *
     * Dispatcher: 他の ViewModel と同じ理由で Dispatchers.Default.limitedParallelism(1) を使う。
     * featuredSites/addedIds の読み取り→書き込みが複数スレッドで競合すると購読済み判定の
     * 取りこぼしが起こり得るため、単一スレッドで直列化する。
     *
     * by lazy でシングルトンキャッシュ化：画面回転時に OnboardingViewModel インスタンスが
     * 同じままであることを保証し、onboardingCompleted の再取得（サーバー再問い合わせ）を防ぐ。
     */
    private val _onboardingViewModel: OnboardingViewModel by lazy {
        OnboardingViewModel(
            sourcesCommands = sourcesCommands,
            sourcesQueries = sourcesQueries,
            onboardingCommands = onboardingCommands,
            dispatcher = Dispatchers.Default.limitedParallelism(1),
        )
    }

    fun getOnboardingViewModel(): OnboardingViewModel = _onboardingViewModel

    /**
     * PasskeyCredentialsViewModel（設定画面のパスキー一覧・削除）を生成して返す
     * （フェーズ17 P17・issue #140）。
     *
     * PasskeyRegistrationViewModel/PasskeyLoginViewModel と異なり [PasskeyProvider]
     * （Activity Context 依存）を必要としないため、他の ViewModel と同じく AppContainer に
     * `by lazy` シングルトンとして保持できる（画面回転をまたいで一覧状態を保持する）。
     */
    private val _passkeyCredentialsViewModel: PasskeyCredentialsViewModel by lazy {
        PasskeyCredentialsViewModel(
            sessionQueries = sessionQueries,
            accountCommands = accountCommands,
            dispatcher = Dispatchers.Default.limitedParallelism(1),
        )
    }

    fun getPasskeyCredentialsViewModel(): PasskeyCredentialsViewModel = _passkeyCredentialsViewModel

    /**
     * PasskeyRegistrationViewModel（設定画面「パスキーを追加」）を生成して返す
     * （フェーズ17 P17・issue #140）。
     *
     * WHY `by lazy` シングルトンにしない: [PasskeyProvider] の実体（CredentialManager）は
     * システム UI 表示のため Activity Context を要求し、MainActivity（Activity）の生存期間に
     * 束縛される。MainActivity は画面回転で再生成される（本アプリは configChanges を上書きして
     * いない）ため、Application スコープの AppContainer にはキャッシュできない。呼び出し元
     * （MainActivity.onCreate）が都度そのときの Activity で構築した [passkeyProvider] を渡す
     * ファクトリメソッドにした（登録セレモニーは短時間で完結するモーダル操作のため、回転時に
     * 進行中状態＝isRegistering/errorMessage が失われても実害は小さいと判断）。
     *
     * 手順と hook は [passkeyCommands] が束ねる（登録成功後に一覧へ即座に反映する）。
     */
    fun createPasskeyRegistrationViewModel(passkeyProvider: PasskeyProvider): PasskeyRegistrationViewModel =
        PasskeyRegistrationViewModel(
            passkeyCommands = passkeyCommands(passkeyProvider),
            dispatcher = Dispatchers.Default.limitedParallelism(1),
        )

    /**
     * PasskeyLoginViewModel（ログイン画面「パスキーでログイン」）を生成して返す
     * （フェーズ17 P17・issue #140）。`by lazy` にしない理由は
     * [createPasskeyRegistrationViewModel] と同じ（[PasskeyProvider] の Activity Context 依存）。
     *
     * セッション確立（sessionStore 保存・authState 遷移・FCM 登録フック）は [passkeyCommands] の hook から
     * auth 層に一任する（[AuthViewModel.completePasskeyLogin] 参照）。
     */
    fun createPasskeyLoginViewModel(passkeyProvider: PasskeyProvider): PasskeyLoginViewModel =
        PasskeyLoginViewModel(
            passkeyCommands = passkeyCommands(passkeyProvider),
            dispatcher = Dispatchers.Default.limitedParallelism(1),
        )

    /**
     * パスキーの登録・ログインの手順を [passkeyProvider] ごとに組む。登録とログインの両方の hook を必ず渡す
     * （既定値は無い）。hook のラムダは呼ばれた時点で ViewModel を解決する。
     */
    private fun passkeyCommands(passkeyProvider: PasskeyProvider): PasskeyCommands =
        PasskeyCommands(
            accountApi = accountApi,
            passkeyProvider = passkeyProvider,
            onRegistered = { _passkeyCredentialsViewModel.loadCredentials() },
            onLoginSuccess = { session -> _authViewModel.completePasskeyLogin(session) },
        )
}
