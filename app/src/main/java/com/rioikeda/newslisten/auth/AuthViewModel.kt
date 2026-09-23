package com.rioikeda.newslisten.auth

import com.rioikeda.newslisten.model.LoginResponse
import com.rioikeda.newslisten.model.UserResponse
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.ApiException
import com.rioikeda.newslisten.network.SessionStore
import com.rioikeda.newslisten.preferences.PreferencesStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
     * android S0（SG-R18）で `onLogoutCleanup` から改名（挙動不変。呼出元は logout 経路のみ）。
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
     *
     * **失効経路（[onUnauthorized]）からは呼ばない**（order:12。後始末の呼出元・順序は変えない）。
     */
    private val onSubjectLeave: suspend () -> Unit = {},
    /**
     * 認証確立時（refreshAuth の me 成功・login 成功）に追加で行うフック（フェーズ9・FCM トークン登録）。
     *
     * onSubjectLeave と同じ設計判断: FCM トークン登録の実体（FcmTokenRegistrar）は notification 層に
     * 属し、auth 層から直接依存させると層の依存方向が崩れるため、呼び出し元（AppContainer）が
     * `{ fcmTokenRegistrar.onAuthenticated() }` を注入する形にした。ログイン成功直後・アプリ起動時の
     * 再認証成功時のどちらでも FCM トークンを登録できるよう、Authenticated 遷移の両経路（refreshAuth
     * 内の me 成功パスと login 成功パス）で呼び出す。
     */
    private val onAuthenticated: suspend () -> Unit = {},
) {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /**
     * 直近の refreshAuth 失敗（一時障害のみ。CI-T10・android S0）。
     *
     * `Unauthorized` はトークンを破棄して `Unauthenticated` へ確定するため対象外。
     * `NetworkError` / `HttpError` / `DecodingError` はトークンを保持したまま UI に再試行導線
     * （[retryRefreshAuth]）を出すためにここへ設定する。成功時は `null` に戻す。
     */
    private val _lastFailure = MutableStateFlow<ApiException?>(null)
    val lastFailure: StateFlow<ApiException?> = _lastFailure.asStateFlow()

    /**
     * セッション書込み（save/clear/状態遷移）と logout ガードカウンタを直列化する
     * （CI-S0-5・CI-S0-10・DV-S0-1・#14）。守る対象は [sessionStore] の変更・[_authState]・
     * [_lastFailure]・[logoutsInProgress]。この区間の中では suspend も外部への callback もしない。
     *
     * `Mutex` ではなく Java の組み込みロック（`Any()` + `synchronized`）にしている理由（#14・F5）:
     * [onUnauthorized] は OkHttp のコールバックスレッドから同期的に呼ばれる必要があり、`suspend`
     * にして `Mutex.withLock` を使うと、呼出元（[AuthInterceptor]）が別 scope へ `launch` して
     * 橋渡しせざるを得なくなり、失効通知とそれに続く操作の交差の窓が非決定的に広がる。
     *
     * OkHttp のコールバックスレッドから届く失効通知（[onUnauthorized]）と、同じ ViewModel 上で
     * 実行される refreshAuth/login/logout が異なるタイミングでセッションを書き換えると、古い通知が
     * 新しいセッション（再ログイン後のトークン）を誤って破棄しうる。このロック内で
     * 「付与されたトークンが現在の [sessionStore] の値と一致するときだけ反映する」照合を行う
     * ことで、in-flight の古い結果が新しいセッションを壊さないようにする。
     *
     * lock の順序（H9）: [sessionStore] の実装（[com.rioikeda.newslisten.network.KeystoreSessionStore]）
     * が内部に持つ `storeLock` は、常に `sessionLock` → `storeLock` の一方向でのみ取得する
     * （`storeLock` の中から外へ callback しないため、逆順の取得は起こらない）。
     */
    private val sessionLock = Any()

    /**
     * logout 実行中（サーバ側失効・後始末含む）に届いた失効通知を抑止するガード
     * （CI-S0-10・SG-S0-7 = (a)。order:95-99 で user 承認済み）。[sessionLock] で保護する。
     *
     * `withContext(dispatcher)` に入った直後に加算し `finally` で必ず減算することで、
     * logout 処理が cancel されてもガードが解放され、以後の失効通知が処理される
     * （F10: 減算漏れは以後の失効通知を永久に捨てる欠陥になる）。
     */
    private var logoutsInProgress = 0

    /** [retryRefreshAuth] の重複実行防止（CI-S0-8）。実行中の再呼出は無視する。 */
    private val retryInFlight = AtomicBoolean(false)

    /**
     * [retryRefreshAuth] 用の内部 CoroutineScope（CI-S0-8）。
     *
     * UI（Compose の `rememberCoroutineScope`）から起動すると、`Authenticated` への遷移で
     * 分岐が composition を離れて scope が cancel され、refreshAuth が完走しない
     * （SR-10 / C3）。呼出元 scope に依存せず完走させるため、ViewModel 自身が scope を持つ。
     */
    private val internalScope = CoroutineScope(SupervisorJob() + dispatcher)

    /** [sessionLock] 内で付与トークンが現在値と一致するときだけ [block] を適用する。適用有無を返す。 */
    private fun applyIfCurrent(attachedToken: String?, block: () -> Unit): Boolean =
        synchronized(sessionLock) {
            if (sessionStore.load() == attachedToken) {
                block()
                true
            } else {
                false
            }
        }

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
     * android S0（CI-T10）: `Unauthorized`（保存済みトークンの失効）のみトークンを破棄して
     * `Unauthenticated` へ確定する。`NetworkError` / `HttpError` / `DecodingError`（一時障害）は
     * トークンを保持したまま現在の [authState] を変えず（起動直後の初回失敗なら `Unknown` の
     * まま、画面回転等で `Authenticated` 中に再実行して一時障害が起きても `Authenticated` を
     * 維持する。F-2）、[lastFailure] を立てて UI に再試行導線を出す（旧仕様は catch-all で
     * 一時障害でもトークンを破棄していたが、これを反転する）。
     *
     * 結果の反映は [applyIfCurrent] で「トークン取得を開始した時点の値がまだ現在値と一致するか」を
     * 照合してから行う（CI-S0-5）。me() 実行中に失効通知や再ログインが割り込んだ場合、
     * 古い結果で新しいセッションを上書きしない。
     *
     * 開始時に [_lastFailure] を `null` に戻す（CI-T10。再試行の間は UI がローディング表示に戻る。
     * CI-S0-6）。
     */
    suspend fun refreshAuth(): Unit = withContext(dispatcher) {
        synchronized(sessionLock) { _lastFailure.value = null }
        val token = sessionStore.load()
        if (token == null) {
            // トークン無しの経路も、lock の中で load()==null を確かめてから書く（CI-S0-5）。
            applyIfCurrent(null) {
                _authState.value = AuthState.Unauthenticated
            }
            return@withContext
        }
        try {
            val user = apiClient.me()
            val applied = applyIfCurrent(token) {
                _authState.value = AuthState.Authenticated(user)
                _lastFailure.value = null
            }
            if (applied) {
                // 認証確立後、サーバーの preferences を同期する（失敗時は既存のローカル値を保持）。
                syncPreferences()
                onAuthenticated()
            }
        } catch (e: ApiException.Unauthorized) {
            applyIfCurrent(token) {
                sessionStore.clear()
                _authState.value = AuthState.Unauthenticated
                _lastFailure.value = null
            }
        } catch (e: ApiException) {
            // 一時障害: authState には触れない（Unknown へ落として再ログイン画面を出してしまわないため）。
            // 画面回転等で Authenticated 中に再実行された一時障害、または並行する refreshAuth が
            // 先に Authenticated へ遷移させていた場合は完全に無視する（F-2・third_vote_completion
            // CI-T10 race 修正: 判定を開始時のスナップショットではなく、書込みと同じ sessionLock 区間内の
            // 現在値で行い、Authenticated かつ lastFailure != null になる交差を防ぐ）。
            applyIfCurrent(token) {
                if (_authState.value !is AuthState.Authenticated) {
                    _lastFailure.value = e
                }
            }
        }
    }

    /**
     * OkHttp から届く失効通知を受け取る（android S0・CI-T11・CI-S0-5・CI-S0-10・DV-S0-1・#14）。
     *
     * [AuthInterceptor.onUnauthorized]（OkHttp のコールバックスレッドから同期的に呼ばれる）が
     * 「三点一致かつ `Authorization` を付与したリクエストの 401」のときのみ呼ぶため、送信時に
     * 付与したトークン（[attachedToken]）を受け取り、[sessionLock] 内で「logout 実行中でない」
     * かつ「現在のセッションと一致する」ときだけ破棄する（両方の確認を同じ排他区間で行う。F3）。
     * logout 実行中（サーバ側失効・後始末含む）は無視する（CI-S0-10・SG-S0-7 = (a)）。
     *
     * [onSubjectLeave] は呼ばない（order:12。失効経路は後始末を呼ばない、既存の slice）。
     */
    fun onUnauthorized(attachedToken: String) {
        synchronized(sessionLock) {
            if (logoutsInProgress > 0) return
            if (sessionStore.load() != attachedToken) return
            sessionStore.clear()
            _authState.value = AuthState.Unauthenticated
            _lastFailure.value = null
        }
    }

    /**
     * [refreshAuth] を呼出元の scope に依存せず内部 scope で実行する（CI-S0-8）。
     *
     * UI（`Unknown` 分岐の再試行ボタン、CI-S0-6）は `rememberCoroutineScope` を使わず、
     * この非 suspend メソッドを直接呼ぶ。実行中の重複呼出は無視する（dedup）。
     */
    fun retryRefreshAuth() {
        if (!retryInFlight.compareAndSet(false, true)) return
        internalScope.launch {
            try {
                refreshAuth()
            } finally {
                retryInFlight.set(false)
            }
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
            preferences.weeklyGoalEpisodes?.let { preferencesStore.setWeeklyGoalEpisodes(it) }
            _preferencesSyncFailed.value = false
        } catch (e: ApiException) {
            _preferencesSyncFailed.value = true
        }
    }

    /**
     * ログインを実行する。成功でトークン保存 + Authenticated、失敗で [loginErrorMessage] を設定する。
     *
     * ユーザー存在を露出しない汎用文言に丸める方針は iOS LoginViewModel.swift 準拠
     * （`Unauthorized` のみ専用文言、429 含むそれ以外は汎用文言）。
     *
     * android S0（CI-T21・leakage guard）: 401 か否かは [ApiException.HttpError.code] の数値比較では
     * なく [ApiException.Unauthorized] の型で判定する（`network/` の外でステータスコード比較を残さない）。
     */
    suspend fun login(username: String, password: String): Unit = withContext(dispatcher) {
        val trimmedUsername = username.trim()
        if (trimmedUsername.isEmpty() || password.isEmpty()) {
            _loginErrorMessage.value = "ユーザーIDとパスワードを入力してください"
            return@withContext
        }
        try {
            val response = apiClient.login(trimmedUsername, password)
            // save → loginErrorMessage=null → Authenticated → lastFailure=null を1つの区間に
            // まとめる（CI-S0-5・T6-6）。onAuthenticated() は lock の外で呼ぶ。
            val saved = synchronized(sessionLock) {
                if (sessionStore.save(response.token)) {
                    _loginErrorMessage.value = null
                    _authState.value = AuthState.Authenticated(response.user)
                    _lastFailure.value = null
                    true
                } else {
                    false
                }
            }
            if (!saved) {
                // CI-T14: 保存失敗時は Authenticated へ遷移させない。
                _loginErrorMessage.value = SESSION_SAVE_ERROR_MESSAGE
                return@withContext
            }
            onAuthenticated()
        } catch (e: ApiException.Unauthorized) {
            _loginErrorMessage.value = "ユーザーIDまたはパスワードが正しくありません"
        } catch (e: ApiException) {
            // 429 (RateLimited) を含め、Unauthorized 以外はユーザー存在を露出しない汎用文言に丸める。
            _loginErrorMessage.value = GENERIC_LOGIN_ERROR_MESSAGE
        }
    }

    /**
     * ログアウトしてサーバ側セッションを破棄し、ローカル状態を未認証にする。
     * サーバ失効に失敗してもローカルのトークン・状態は必ず落とす（iOS `try?` 相当のベストエフォート）。
     *
     * android S0（CI-S0-10・SG-S0-7 = (a)）: 実行中（サーバ側失効・後始末含む）は [logoutsInProgress]
     * を立てて [onUnauthorized] を抑止する。`withContext` に入った直後、減算の `finally` を持つ
     * `try` の直前に [sessionLock] の中で加算する（増加と `try` の間に suspend する地点を挟まない）。
     * `try` には `apiClient.logout()` を含めるため、cancel されても `finally` が必ず走り、ガードが
     * 解放されて以後の失効通知を捨て続けない（F10）。
     *
     * CI-S0-10 の WHY（経路 B）: logout の途中でサーバ側がセッションを削除するため、同じ時間帯に
     * 別の要求が 401 を受け得る。後始末の完了前に遷移させない（order:57）。
     */
    suspend fun logout(): Unit = withContext(dispatcher) {
        synchronized(sessionLock) { logoutsInProgress++ }
        try {
            try {
                apiClient.logout()
            } catch (e: ApiException) {
                // ベストエフォート: サーバ側の失効に失敗してもローカルの認証状態は落とす。
            }
            try {
                onSubjectLeave()
            } catch (e: Exception) {
                // ベストエフォート（spec §6.3）: キャッシュ削除の失敗でログアウト自体を失敗させない。
                // onSubjectLeave は呼び出し元が注入する任意の処理のため、ApiException に限定せず
                // 汎用 Exception で受ける（クライアント側の状態は信頼できない前提）。
            }
            synchronized(sessionLock) {
                sessionStore.clear()
                _authState.value = AuthState.Unauthenticated
                _lastFailure.value = null
            }
        } finally {
            synchronized(sessionLock) { logoutsInProgress-- }
        }
    }

    /**
     * パスキーログイン成功後にセッションを確立する（フェーズ17 P17）。
     *
     * [login] のトークン保存以降の処理（sessionStore.save → authState 遷移 → onAuthenticated）と
     * 同じ手順を踏む。パスキー認証セレモニー自体（options 取得・Credential Manager 呼び出し・
     * verify）は [com.rioikeda.newslisten.passkey.PasskeyLoginViewModel] の責務であり、
     * ここでは verify 成功後の [LoginResponse] を受け取ってセッション確立のみを行う
     * （auth 層がセッションの正本という既存の責務分担を維持するため、passkey 層から
     * sessionStore/authState を直接操作させない設計）。
     *
     * android S0（CI-S0-3）: [login] と同じ保存失敗の保証を持つ。
     */
    suspend fun completePasskeyLogin(response: LoginResponse): Unit = withContext(dispatcher) {
        // login と同じく、save → loginErrorMessage=null → Authenticated → lastFailure=null を
        // 1つの区間にまとめる（CI-S0-5・T6-6）。onAuthenticated() は lock の外で呼ぶ。
        val saved = synchronized(sessionLock) {
            if (sessionStore.save(response.token)) {
                _loginErrorMessage.value = null
                _authState.value = AuthState.Authenticated(response.user)
                _lastFailure.value = null
                true
            } else {
                false
            }
        }
        if (!saved) {
            _loginErrorMessage.value = SESSION_SAVE_ERROR_MESSAGE
            return@withContext
        }
        onAuthenticated()
    }

    /**
     * プロフィール更新（表示名）成功後に [authState] へ反映する（フェーズ11 P11 T2）。
     *
     * iOS は appState.currentUser を直接更新する（AccountSettingsView.swift:308）が、Android は
     * AuthState.Authenticated.user が不変・_authState が private のため、更新結果（呼び出し元の
     * ApiClient.updateProfile 戻り値）を渡してもらい、ここで新しい Authenticated を発行する設計にした。
     * ログアウト後の非同期競合等、Authenticated 以外の状態では何もしない（no-op）。
     */
    fun applyProfileUpdate(updatedUser: UserResponse) {
        // 判定と書込みを sessionLock の中で行う（CI-S0-5・T6-6。失効との競合で復活させない）。
        synchronized(sessionLock) {
            if (_authState.value is AuthState.Authenticated) {
                _authState.value = AuthState.Authenticated(updatedUser)
            }
        }
    }

    private companion object {
        const val GENERIC_LOGIN_ERROR_MESSAGE = "ログインに失敗しました。接続設定を確認してください"

        /** CI-T14・CI-S0-3: SessionStore.save 失敗時の文言（spec §3 wording）。 */
        const val SESSION_SAVE_ERROR_MESSAGE = "ログイン状態を端末に保存できませんでした。もう一度お試しください"
    }
}
