package com.rioikeda.newslisten.di

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.firebase.messaging.FirebaseMessaging
import com.rioikeda.newslisten.BuildConfig
import com.rioikeda.newslisten.auth.AuthState
import com.rioikeda.newslisten.auth.AuthViewModel
import com.rioikeda.newslisten.feed.FeedViewModel
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.AudioCacheManager
import com.rioikeda.newslisten.network.AuthInterceptor
import com.rioikeda.newslisten.network.ConnectivityNetworkMonitor
import com.rioikeda.newslisten.network.JavaFileSystem
import com.rioikeda.newslisten.network.KeystoreSessionStore
import com.rioikeda.newslisten.network.OkHttpApiClient
import com.rioikeda.newslisten.network.SessionStore
import com.rioikeda.newslisten.notification.FcmTokenRegistrar
import com.rioikeda.newslisten.podcast.ExoPlayerController
import com.rioikeda.newslisten.podcast.PodcastViewModel
import com.rioikeda.newslisten.preferences.DataStorePreferencesStore
import com.rioikeda.newslisten.preferences.PreferencesStore
import com.rioikeda.newslisten.settings.SettingsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

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
     * PodcastViewModel.play() の resolvePlaybackSource 判定に isOnline を供給する。
     */
    private val networkMonitor: ConnectivityNetworkMonitor = ConnectivityNetworkMonitor(appContext).apply { start() }

    /**
     * FCM トークン取得の suspend ラッパ（フェーズ9・プッシュ通知）。
     *
     * WHY suspendCancellableCoroutine: kotlinx-coroutines-play-services への追加依存
     * (Task.await()) を避け、OkHttpApiClient.executeCall と同型のパターンで既存の
     * Task API(addOnCompleteListener) をラップする。取得失敗時は例外を投げず null を返し、
     * 「トークン取得できない」として FcmTokenRegistrar 側に判断を委ねる。
     *
     * WHY @Suppress("DEPRECATION"): firebase-bom 34.15.0 (firebase-messaging 25.1.0) では
     * `FirebaseMessaging.getToken()` が非推奨化されている（コンパイル時に確認済み。register() は
     * Task<Void> しか返さずトークン文字列を取得できないため代替にならない）。ログイン時に
     * その場でトークンを pull 取得する用途では、現時点で Google 公式ドキュメントもこの API を
     * 案内しており、機能上は問題ない。SDK の将来のメジャー更新で本当に削除される場合は
     * FcmTokenRegistrar のインターフェース（tokenProvider: suspend () -> String?）は変えずに
     * ここだけ差し替えればよい設計にしてある。
     */
    @Suppress("DEPRECATION")
    private val fcmTokenProvider: suspend () -> String? = {
        suspendCancellableCoroutine { continuation ->
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                continuation.resume(if (task.isSuccessful) task.result else null)
            }
        }
    }

    /**
     * FCM デバイストークンの登録・解除ロジック（フェーズ9）。
     *
     * permissionChecker: API 33 未満は POST_NOTIFICATIONS 権限自体が存在しないため常に許可扱い
     * （MainActivity のランタイム要求ガードと同じ判定基準）。
     */
    private val fcmTokenRegistrar: FcmTokenRegistrar = FcmTokenRegistrar(
        apiClient = apiClient,
        tokenProvider = fcmTokenProvider,
        permissionChecker = {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        },
        dispatcher = Dispatchers.Default,
    )

    fun getFcmTokenRegistrar(): FcmTokenRegistrar = fcmTokenRegistrar

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

    fun getPreferencesStore(): PreferencesStore = preferencesStore

    /**
     * AuthViewModel（認証状態ゲーティング + ログイン）を生成して返す。
     *
     * Dispatcher: Dispatchers.Default は CPU バウンドタスク向き。IO タスクには Dispatchers.IO を
     * 推奨するが、login/refreshAuth は mainly ネットワーク IO であり、OkHttpClient が内部で
     * スレッドプールを持つため、Default でも問題ない（スレッド枯渇リスクは低い）。テスト時に
     * StandardTestDispatcher 差し替えで同期化テストを可能にする設計。
     *
     * by lazy でシングルトンキャッシュ化：画面回転時に AuthViewModel インスタンスが同じ
     * ままであることを保証し、authState が Unknown にリセットされるのを防ぐ。
     *
     * onLogoutCleanup: フェーズ8-D・shared-playback-spec.md §6.3（共有端末対応）。logout 時に
     * 音声キャッシュを全削除し、共有端末に他人の音声データが残らないようにする。auth 層に
     * AudioCacheManager を直接依存させないため、削除処理だけを関数として注入する
     * （詳細は AuthViewModel の onLogoutCleanup コメント参照）。
     *
     * 2レビュー統合指摘（logout×ダウンロード競合）の修正: 直接 `audioCacheManager.removeAll()` を
     * 呼ぶのではなく `_podcastViewModel.cancelDownloadsAndClearCache()` を呼ぶ。PodcastViewModel の
     * download() は AudioCacheManager と同じ limitedParallelism(1) dispatcher 上で進行中 Job を
     * 追跡しており、cancelDownloadsAndClearCache() がそれを cancelAndJoin してから removeAll() する
     * ことで、fetchPodcast の suspend 境界中の download と logout のキャッシュ削除が競合し
     * ファイルが残留する事態を防ぐ。
     */
    private val _authViewModel: AuthViewModel by lazy {
        AuthViewModel(
            apiClient = apiClient,
            sessionStore = sessionStore,
            dispatcher = Dispatchers.Default,
            preferencesStore = preferencesStore,
            // フェーズ9: cancelDownloadsAndClearCache（既存・フェーズ8-D）と FCM トークン解除を
            // 両方行う合成ラムダ。onLogoutCleanup は単一のフック点のため、複数の後始末はここで束ねる。
            // 各操作を独立した try/catch で保護し、前段の失敗が後段をスキップさせないようにする。
            onLogoutCleanup = {
                try {
                    _podcastViewModel.cancelDownloadsAndClearCache()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // キャッシュクリア失敗はログアウト継続
                }
                try {
                    fcmTokenRegistrar.onLogout()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // FCM トークン解除失敗はログアウト継続
                }
            },
            onAuthenticated = { fcmTokenRegistrar.onAuthenticated() },
        )
    }

    fun getAuthViewModel(): AuthViewModel = _authViewModel

    /**
     * FeedViewModel（フィード一覧・Star/Dismiss・Undo）を生成して返す。
     *
     * Dispatcher: Dispatchers.Default.limitedParallelism(1) を使用し、状態変更を直列化する。
     * WHY: FeedViewModel の読み取り→書き込み（_pendingAction.value の読み取り、null 代入、commit 送信）と
     * stage の読み取り→変更→書き込み（_articles の読み取り、removeAt、_pendingAction 代入）が別スレッドで
     * 競合すると、二重送信や記事消失が起こり得る。iOS の @MainActor による直列実行を Kotlin で再現するため、
     * limitedParallelism(1) で全操作を単一スレッドで順序付ける。testDispatcher で差し替え可能。
     *
     * by lazy でシングルトンキャッシュ化：画面回転時に FeedViewModel インスタンスが同じ
     * ままであることを保証し、フィード一覧の状態が保持される。
     */
    private val _feedViewModel: FeedViewModel by lazy {
        FeedViewModel(
            apiClient = apiClient,
            dispatcher = Dispatchers.Default.limitedParallelism(1),
            preferencesStore = preferencesStore,
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
     * PodcastViewModel（Podcast タブの状態とロジック）を生成して返す。
     *
     * Dispatcher: Dispatchers.Default.limitedParallelism(1) を使用し、状態変更を直列化する。
     * WHY: PodcastViewModel の fetchPodcasts() や play() などの操作が複数スレッドで
     * 競合すると、currentPodcast の読み取り→書き込み、PlayerController の状態の
     * 同期ずれが生じ得る。iOS の @MainActor による直列実行を Kotlin で再現する。
     *
     * by lazy でシングルトンキャッシュ化：画面回転時に PodcastViewModel インスタンスが
     * 同じままであることを保証し、fetchPodcasts() の loadingState や currentPodcast が
     * リセットされるのを防ぐ。
     */
    private val _podcastViewModel: PodcastViewModel by lazy {
        PodcastViewModel(
            apiClient = apiClient,
            playerController = _playerController,
            cacheManager = audioCacheManager,
            networkMonitor = networkMonitor,
            dispatcher = Dispatchers.Default.limitedParallelism(1)
        )
    }

    fun getPodcastViewModel(): PodcastViewModel = _podcastViewModel

    /**
     * SettingsViewModel（設定タブ: RSS ソース管理・おすすめサイト・生成クォータ・聴取ストリーク・
     * 難易度/再生速度のサーバー同期）を生成して返す（フェーズ10 P10 Task2）。
     *
     * Dispatcher: FeedViewModel/PodcastViewModel と同じ理由で
     * Dispatchers.Default.limitedParallelism(1) を使う。sources リストの読み取り→書き込みが
     * 複数スレッドで競合すると更新の取りこぼしが起こり得るため、単一スレッドで直列化する。
     *
     * isAdminProvider: RSS ソース編集（updateSource）は admin 限定（issue #66・ADR-047）。
     * settings 層が auth 層の型（AuthState）に直接依存しないよう、AuthViewModel の
     * onLogoutCleanup/onAuthenticated と同じ「呼び出し元が判定関数を注入する」パターンを踏襲する。
     * role は認証確立後に非同期で確定するため、コンストラクタ時点の固定値ではなく
     * 呼び出し時点で都度 _authViewModel.authState.value を評価する。
     *
     * by lazy でシングルトンキャッシュ化：画面回転時に SettingsViewModel インスタンスが
     * 同じままであることを保証し、sources/generationQuota 等の読み込み済み状態を保持する。
     */
    private val _settingsViewModel: SettingsViewModel by lazy {
        SettingsViewModel(
            apiClient = apiClient,
            preferencesStore = preferencesStore,
            dispatcher = Dispatchers.Default.limitedParallelism(1),
            isAdminProvider = {
                (_authViewModel.authState.value as? AuthState.Authenticated)?.user?.role == "admin"
            },
        )
    }

    fun getSettingsViewModel(): SettingsViewModel = _settingsViewModel
}
