package com.rioikeda.newslisten.auth

import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.ApiException
import com.rioikeda.newslisten.network.SessionStore
import com.rioikeda.newslisten.preferences.PreferencesStore
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
    /**
     * 難易度・再生速度の値の正本（フェーズ10 P10 Task3）。AuthViewModel は独自コピーを持たず、
     * このストアの StateFlow をそのまま公開し、同期成功時にこのストアへ書き戻すのみを行う
     * （「どちらが最新か」の二重管理を避ける設計。詳細は [PreferencesStore] のコメント参照）。
     */
    private val preferencesStore: PreferencesStore,
    /**
     * logout 時に追加で行うクリーンアップ（フェーズ8-D・shared-playback-spec.md §6.3）。
     *
     * spec §6.3 は共有端末対応として「logout 時にユーザー固有キャッシュ（音声）を完全削除する」ことを
     * 全プラットフォーム共通の方針として定めるが、実体（AudioCacheManager）は podcast/network 層に属し、
     * auth 層から直接依存させると層の依存方向が逆転する（auth → podcast）。そのため呼び出し元
     * （AppContainer）が `{ podcastViewModel.cancelDownloadsAndClearCache() }` を注入する形にし、
     * auth 層は「ログアウト時に何かクリーンアップが要る」という事実のみを知る設計にした。
     *
     * suspend である理由（2レビュー統合指摘・logout×ダウンロード競合の修正）: PodcastViewModel の
     * download() は fetchPodcast という suspend 境界を挟むため、単純な `() -> Unit`（同期・fire-and-forget
     * 相当）では進行中のダウンロードを待たずに戻ってしまい、キャッシュ削除後にダウンロードが完了して
     * ファイルが復活する競合が起こり得た。cancelDownloadsAndClearCache() が進行中の Job を
     * cancelAndJoin してからキャッシュを削除するため、ここも suspend にしてその完了を待つ。
     *
     * 注記: iOS (AppState.swift:171-178) の logout() は現時点でキャッシュ削除を呼び出しておらず、
     * spec §6.3 の表が言う「ビルトイン」実装とは実際には未配線（反証済み）。ADR-053 の
     * 「spec が正本・実装が追随」原則に従い、Android は spec 準拠で先行実装する。iOS 側の追随は
     * 別途 docs 側の follow-up として扱う。
     */
    private val onLogoutCleanup: suspend () -> Unit = {},
    /**
     * 認証確立時（refreshAuth の me 成功・login 成功）に追加で行うフック（フェーズ9・FCM トークン登録）。
     *
     * onLogoutCleanup と同じ設計判断: FCM トークン登録の実体（FcmTokenRegistrar）は notification 層に
     * 属し、auth 層から直接依存させると層の依存方向が崩れるため、呼び出し元（AppContainer）が
     * `{ fcmTokenRegistrar.onAuthenticated() }` を注入する形にした。ログイン成功直後・アプリ起動時の
     * 再認証成功時のどちらでも FCM トークンを登録できるよう、Authenticated 遷移の両経路（refreshAuth
     * 内の me 成功パスと login 成功パス）で呼び出す。
     */
    private val onAuthenticated: suspend () -> Unit = {},
) {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /** 値の正本は [preferencesStore]（フェーズ10 P10 Task3）。ここでは委譲するのみ。 */
    val defaultDifficulty: StateFlow<String> = preferencesStore.defaultDifficulty

    /** 値の正本は [preferencesStore]（フェーズ10 P10 Task3）。ここでは委譲するのみ。 */
    val defaultPlaybackSpeed: StateFlow<Double> = preferencesStore.defaultPlaybackSpeed

    /**
     * 直近の preferences 同期が失敗したか（issue #164 同型。既存値は保持したまま可視化のみ行う）。
     *
     * WHY AuthViewModel が保持する（[PreferencesStore] に置かない）: この値は「直近の同期試行の
     * 結果」という auth/sync フロー固有の一時的な UI シグナルであり、再起動をまたいで
     * 永続化すべきユーザー設定値ではない（再起動後は refreshAuth() が再評価する）。
     * PreferencesStore の契約を「値そのもの」に保つため、揮発性フラグはここに留める。
     */
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
            onAuthenticated()
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
            preferencesStore.setDefaultDifficulty(preferences.defaultDifficulty)
            preferencesStore.setDefaultPlaybackSpeed(preferences.defaultPlaybackSpeed)
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
            onAuthenticated()
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
        try {
            onLogoutCleanup()
        } catch (e: Exception) {
            // ベストエフォート（spec §6.3）: キャッシュ削除の失敗でログアウト自体を失敗させない。
            // onLogoutCleanup は呼び出し元が注入する任意の処理のため、ApiException に限定せず
            // 汎用 Exception で受ける（クライアント側の状態は信頼できない前提）。
        }
        sessionStore.clear()
        _authState.value = AuthState.Unauthenticated
    }

    private companion object {
        const val GENERIC_LOGIN_ERROR_MESSAGE = "ログインに失敗しました。接続設定を確認してください"
    }
}
