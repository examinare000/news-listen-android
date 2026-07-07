package com.rioikeda.newslisten.di

import android.content.Context
import com.rioikeda.newslisten.BuildConfig
import com.rioikeda.newslisten.auth.AuthViewModel
import com.rioikeda.newslisten.feed.FeedViewModel
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.AudioCacheManager
import com.rioikeda.newslisten.network.AuthInterceptor
import com.rioikeda.newslisten.network.JavaFileSystem
import com.rioikeda.newslisten.network.KeystoreSessionStore
import com.rioikeda.newslisten.network.OkHttpApiClient
import com.rioikeda.newslisten.network.SessionStore
import com.rioikeda.newslisten.podcast.ExoPlayerController
import com.rioikeda.newslisten.podcast.PodcastViewModel
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
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
     * AuthViewModel（認証状態ゲーティング + ログイン）を生成して返す。
     *
     * Dispatcher: Dispatchers.Default は CPU バウンドタスク向き。IO タスクには Dispatchers.IO を
     * 推奨するが、login/refreshAuth は mainly ネットワーク IO であり、OkHttpClient が内部で
     * スレッドプールを持つため、Default でも問題ない（スレッド枯渇リスクは低い）。テスト時に
     * StandardTestDispatcher 差し替えで同期化テストを可能にする設計。
     *
     * by lazy でシングルトンキャッシュ化：画面回転時に AuthViewModel インスタンスが同じ
     * ままであることを保証し、authState が Unknown にリセットされるのを防ぐ。
     */
    private val _authViewModel: AuthViewModel by lazy {
        AuthViewModel(
            apiClient = apiClient,
            sessionStore = sessionStore,
            dispatcher = Dispatchers.Default
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
            dispatcher = Dispatchers.Default.limitedParallelism(1)
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
            dispatcher = Dispatchers.Default.limitedParallelism(1)
        )
    }

    fun getPodcastViewModel(): PodcastViewModel = _podcastViewModel
}
