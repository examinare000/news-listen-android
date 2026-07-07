package com.rioikeda.newslisten.auth

import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.ApiException
import com.rioikeda.newslisten.network.SessionStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 認証状態ゲーティングとログインを担う ViewModel（フェーズ3）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/AppState.swift:130-178
 * （refreshAuth/refreshPreferences/logout）、
 * ios/NewsListenApp/NewsListenApp/Auth/LoginViewModel.swift（ログインのエラー文言）のミラー。
 *
 * androidx.lifecycle.ViewModel は継承しない。viewModelScope を直書きすると Dispatcher を
 * テストで差し替えられず TDD の Red-Green サイクルが回せないため、iOS の `AppState`
 * （ただの ObservableObject）と同様にプレーンな Kotlin クラスとして実装し、Dispatcher を
 * コンストラクタ注入する。呼び出し元（Compose 側）は `viewModelScope.launch { }` から
 * 各 suspend 関数を呼び出す想定。
 */
class AuthViewModel(
    private val apiClient: ApiClient,
    private val sessionStore: SessionStore,
    private val dispatcher: CoroutineDispatcher,
) {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _defaultDifficulty = MutableStateFlow(DEFAULT_DIFFICULTY)
    val defaultDifficulty: StateFlow<String> = _defaultDifficulty.asStateFlow()

    private val _defaultPlaybackSpeed = MutableStateFlow(DEFAULT_PLAYBACK_SPEED)
    val defaultPlaybackSpeed: StateFlow<Double> = _defaultPlaybackSpeed.asStateFlow()

    /** 直近の preferences 同期が失敗したか（issue #164 同型。既存値は保持したまま可視化のみ行う）。 */
    private val _preferencesSyncFailed = MutableStateFlow(false)
    val preferencesSyncFailed: StateFlow<Boolean> = _preferencesSyncFailed.asStateFlow()

    private val _loginErrorMessage = MutableStateFlow<String?>(null)
    val loginErrorMessage: StateFlow<String?> = _loginErrorMessage.asStateFlow()

    /**
     * 保存済みトークンで /auth/me を解決し、認証状態を確定する。
     *
     * トークン未保存・失効・通信不可はすべて未認証として扱いトークンを破棄する
     * （iOS AppState.swift:132-147 準拠。エラー種別を区別しない catch-all）。
     */
    suspend fun refreshAuth(): Unit = withContext(dispatcher) {
        val token = sessionStore.load()
        if (token == null) {
            _authState.value = AuthState.Unauthenticated
            return@withContext
        }
        try {
            val user = apiClient.me()
            _authState.value = AuthState.Authenticated(user)
            // 認証確立後、サーバーの preferences を同期する（失敗時は既存のローカル値を保持）。
            syncPreferences()
        } catch (e: ApiException) {
            sessionStore.clear()
            _authState.value = AuthState.Unauthenticated
        }
    }

    /**
     * サーバーから preferences を取得し、ローカルの defaultDifficulty/defaultPlaybackSpeed を更新する。
     * 取得失敗時は既存値を保持しつつ [preferencesSyncFailed] を立てる（issue #164）。
     */
    private suspend fun syncPreferences() {
        try {
            val preferences = apiClient.fetchPreferences()
            _defaultDifficulty.value = preferences.defaultDifficulty
            _defaultPlaybackSpeed.value = preferences.defaultPlaybackSpeed
            _preferencesSyncFailed.value = false
        } catch (e: ApiException) {
            _preferencesSyncFailed.value = true
        }
    }

    /**
     * ログインを実行する。成功でトークン保存 + Authenticated、失敗で [loginErrorMessage] を設定する。
     *
     * ユーザー存在を露出しない汎用文言に丸める方針は iOS LoginViewModel.swift 準拠
     * （401 のみ専用文言、429 含むそれ以外は汎用文言）。
     */
    suspend fun login(username: String, password: String): Unit = withContext(dispatcher) {
        val trimmedUsername = username.trim()
        if (trimmedUsername.isEmpty() || password.isEmpty()) {
            _loginErrorMessage.value = "ユーザーIDとパスワードを入力してください"
            return@withContext
        }
        try {
            val response = apiClient.login(trimmedUsername, password)
            sessionStore.save(response.token)
            _loginErrorMessage.value = null
            _authState.value = AuthState.Authenticated(response.user)
        } catch (e: ApiException.HttpError) {
            _loginErrorMessage.value = if (e.code == 401) {
                "ユーザーIDまたはパスワードが正しくありません"
            } else {
                GENERIC_LOGIN_ERROR_MESSAGE
            }
        } catch (e: ApiException) {
            // 429 (RateLimited) を含め、401 以外はユーザー存在を露出しない汎用文言に丸める。
            _loginErrorMessage.value = GENERIC_LOGIN_ERROR_MESSAGE
        }
    }

    /**
     * ログアウトしてサーバ側セッションを破棄し、ローカル状態を未認証にする。
     * サーバ失効に失敗してもローカルのトークン・状態は必ず落とす（iOS `try?` 相当のベストエフォート）。
     */
    suspend fun logout(): Unit = withContext(dispatcher) {
        try {
            apiClient.logout()
        } catch (e: ApiException) {
            // ベストエフォート: サーバ側の失効に失敗してもローカルの認証状態は落とす。
        }
        sessionStore.clear()
        _authState.value = AuthState.Unauthenticated
    }

    private companion object {
        const val DEFAULT_DIFFICULTY = "toeic_600"
        const val DEFAULT_PLAYBACK_SPEED = 1.0
        const val GENERIC_LOGIN_ERROR_MESSAGE = "ログインに失敗しました。接続設定を確認してください"
    }
}
