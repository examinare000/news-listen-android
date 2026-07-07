package com.rioikeda.newslisten.di

import android.content.Context
import com.rioikeda.newslisten.BuildConfig
import com.rioikeda.newslisten.auth.AuthViewModel
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.AuthInterceptor
import com.rioikeda.newslisten.network.KeystoreSessionStore
import com.rioikeda.newslisten.network.OkHttpApiClient
import com.rioikeda.newslisten.network.SessionStore
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
}
