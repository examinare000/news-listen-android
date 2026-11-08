package com.rioikeda.newslisten.auth

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.model.LoginResponse
import com.rioikeda.newslisten.model.UserResponse
import com.rioikeda.newslisten.network.ApiClient
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
     * 主体が離れたときに呼ぶフック（ADR-104 決定 1・3、shared-leave-01・02・07）。離脱は認証状態の遷移から導出する:
     * 直前が `Authenticated(A)` で、次が `Unauthenticated` か別主体の `Authenticated(B)` のとき、離脱主体 A を引数に
     * [sessionLock] の外で 1 回呼ぶ。トークンの破棄と新しい状態の確定は呼出より先に済んでいる。
     * 同一主体の更新、`Unknown` / `Unauthenticated` からの遷移では呼ばない。
     *
     * 非 suspend で、呼出元は戻りを待たない（後始末の完了を待たずに遷移する）。例外は握り、状態も送信も巻き戻さない。
     * 実体（再生停止・ダウンロード中断・音声キャッシュ削除・端末設定の削除・通知の印の初期化）は他の層に属するため、
     * 呼び出し元（AppContainer）が手順列として束ねて注入し、auth 層は「主体が離れた」という事実だけを知る。
     */
    private val onSubjectLeave: (Subject) -> Unit = {},
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
    /**
     * `Unknown` からの確定の直後と、遷移 ④（`Authenticated(A)` から別主体 B への確立）の直後に、それぞれ 1 回呼ぶ
     * （起動時回収。shared-leave-07・08・09、android-dec-48）。引数は確定後の主体（`Unauthenticated` なら null）。
     * 一時障害で `Unknown` のままの間、確定の後の同一主体の login・再実行では呼ばない。遷移 ④ の呼出は
     * shared-leave-09 の回数に入れない。[sessionLock] の外で呼び、離脱の通知（[onSubjectLeave]）の後に呼ぶ。
     */
    private val onSubjectResolved: (Subject?) -> Unit = {},
) {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /** 認証済みなら、現在の主体の id（形式が不正・欠落なら null）。未認証・`Unknown` も null。 */
    fun currentSubjectId(): String? =
        (authState.value as? AuthState.Authenticated)?.let { Subject.from(it.user).userId }

    /**
     * 直近の refreshAuth 失敗（一時障害のみ。android-contract-12・android-slice-auth-01）。
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
     * logout の同期部分（トークンの捕捉から離脱の通知まで）に届いた失効通知を抑止するガード
     * （CI-S0-10・SG-S0-7 = (a)）。[sessionLock] で保護する。抑止は、このカウンタが正であることと、付与トークンが
     * 現在値と一致することの両方で行う。
     *
     * `withContext(dispatcher)` に入った直後に加算し `finally` で必ず減算することで、
     * logout 処理が cancel されてもガードが解放され、以後の失効通知が処理される
     * （F10: 減算漏れは以後の失効通知を永久に捨てる欠陥になる）。
     */
    private var logoutsInProgress = 0

    /** [retryRefreshAuth] の重複実行防止（CI-S0-8）。実行中の再呼出は無視する。 */
    private val retryInFlight = AtomicBoolean(false)

    /**
     * 内部 CoroutineScope（CI-S0-8）。[retryRefreshAuth] の実行と、logout のサーバ送信（完了を待たない）に使う。
     *
     * UI（Compose の `rememberCoroutineScope`）から起動すると、`Authenticated` への遷移で
     * 分岐が composition を離れて scope が cancel され、refreshAuth が完走しない
     * （SR-10 / C3）。呼出元 scope に依存せず完走させるため、ViewModel 自身が scope を持つ。
     */
    private val internalScope = CoroutineScope(SupervisorJob() + dispatcher)

    /** 認証状態の遷移。[left] は離脱する主体（離脱でなければ null）。 */
    private class Transition(val previous: AuthState, val left: Subject?)

    /**
     * 認証状態の唯一の書込口。[sessionLock] の中で呼ぶ。離脱は直前が `Authenticated(A)` で、次が `Unauthenticated` か
     * A と別主体の `Authenticated(B)` のときだけ導出する（共有仕様 6.5）。`Unknown` に戻す遷移は存在しない。
     */
    private fun setAuthState(next: AuthState): Transition {
        val previous = _authState.value
        val left = (previous as? AuthState.Authenticated)?.let { Subject.from(it.user) }?.takeUnless { leaving ->
            next is AuthState.Authenticated && leaving.isSameSubjectAs(Subject.from(next.user))
        }
        _authState.value = next
        return Transition(previous, left)
    }

    /**
     * 遷移の通知。[sessionLock] の外で呼ぶ。離脱があれば [onSubjectLeave] を 1 回、その後に [onSubjectResolved] を
     * （`Unknown` からの確定、または遷移 ④ の B の確立で）1 回呼ぶ。
     */
    private fun announce(transition: Transition, next: AuthState) {
        transition.left?.let { left ->
            try {
                onSubjectLeave(left)
            } catch (e: Exception) {
                // ベストエフォート: 後始末の起動の失敗でログアウトや遷移を失敗させない。
            }
        }
        val resolved = (next as? AuthState.Authenticated)?.let { Subject.from(it.user) }
        when {
            transition.previous is AuthState.Unknown -> onSubjectResolved(resolved)
            transition.left != null && resolved != null -> onSubjectResolved(resolved)
        }
    }

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
     * android-slice-auth-01（android-contract-12）: `Unauthorized`（保存済みトークンの失効）のみトークンを破棄して
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
     * 開始時に [_lastFailure] を `null` に戻す（android-contract-12。再試行の間は UI がローディング表示に戻る。
     * CI-S0-6）。
     */
    suspend fun refreshAuth(): Unit = withContext(dispatcher) {
        synchronized(sessionLock) { _lastFailure.value = null }
        val token = sessionStore.load()
        if (token == null) {
            // トークン無しの経路も、lock の中で load()==null を確かめてから書く（CI-S0-5）。
            var transition: Transition? = null
            applyIfCurrent(null) { transition = setAuthState(AuthState.Unauthenticated) }
            transition?.let { announce(it, AuthState.Unauthenticated) }
            return@withContext
        }
        try {
            val user = apiClient.me()
            val next = AuthState.Authenticated(user)
            var transition: Transition? = null
            val applied = applyIfCurrent(token) {
                transition = setAuthState(next)
                _lastFailure.value = null
            }
            if (applied) {
                transition?.let { announce(it, next) }
                // 認証確立後、サーバーの preferences を同期する（失敗時は既存のローカル値を保持）。
                syncPreferences()
                onAuthenticated()
            }
        } catch (e: ApiException.Unauthorized) {
            var transition: Transition? = null
            applyIfCurrent(token) {
                sessionStore.clear()
                transition = setAuthState(AuthState.Unauthenticated)
                _lastFailure.value = null
            }
            transition?.let { announce(it, AuthState.Unauthenticated) }
        } catch (e: ApiException) {
            // 一時障害: authState には触れない（Unknown へ落として再ログイン画面を出してしまわないため）。
            // 画面回転等で Authenticated 中に再実行された一時障害、または並行する refreshAuth が
            // 先に Authenticated へ遷移させていた場合は完全に無視する（F-2・third_vote_completion
            // android-contract-12 race 修正: 判定を開始時のスナップショットではなく、書込みと同じ sessionLock 区間内の
            // 現在値で行い、Authenticated かつ lastFailure != null になる交差を防ぐ）。
            applyIfCurrent(token) {
                if (_authState.value !is AuthState.Authenticated) {
                    _lastFailure.value = e
                }
            }
        }
    }

    /**
     * OkHttp から届く失効通知を受け取る（android-slice-auth-01・android-contract-13・CI-S0-5・CI-S0-10・DV-S0-1・#14）。
     *
     * [AuthInterceptor.onUnauthorized]（OkHttp のコールバックスレッドから同期的に呼ばれる）が
     * 「三点一致かつ `Authorization` を付与したリクエストの 401」のときのみ呼ぶため、送信時に
     * 付与したトークン（[attachedToken]）を受け取り、[sessionLock] 内で「logout 実行中でない」
     * かつ「現在のセッションと一致する」ときだけ破棄する（両方の確認を同じ排他区間で行う。F3）。
     * logout の同期部分に届いた通知は無視する（CI-S0-10・SG-S0-7 = (a)）。
     *
     * 直前が `Authenticated` なら遷移 ② として [onSubjectLeave] で後始末を起動する（shared-leave-02）。サーバ logout は送らない。
     * `Unknown` からの失効は離脱でなく、起動時回収が拾う。
     */
    fun onUnauthorized(attachedToken: String) {
        val transition = synchronized(sessionLock) {
            if (logoutsInProgress > 0) return
            if (sessionStore.load() != attachedToken) return
            sessionStore.clear()
            val t = setAuthState(AuthState.Unauthenticated)
            _lastFailure.value = null
            t
        }
        announce(transition, AuthState.Unauthenticated)
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
     * android-slice-auth-01（android-contract-23・leakage guard）: 401 か否かはステータスコードの数値比較では
     * なく [ApiException.Unauthorized] の型で判定する（`network/` の外でステータスコード比較を残さない）。
     */
    suspend fun login(username: String, password: String): Unit = withContext(dispatcher) {
        val trimmedUsername = username.trim()
        if (trimmedUsername.isEmpty() || password.isEmpty()) {
            _loginErrorMessage.value = "ユーザーIDとパスワードを入力してください"
            return@withContext
        }
        try {
            establishSession(apiClient.login(trimmedUsername, password))
        } catch (e: ApiException.Unauthorized) {
            _loginErrorMessage.value = "ユーザーIDまたはパスワードが正しくありません"
        } catch (e: ApiException) {
            // 429 (RateLimited) を含め、Unauthorized 以外はユーザー存在を露出しない汎用文言に丸める。
            _loginErrorMessage.value = GENERIC_LOGIN_ERROR_MESSAGE
        }
    }

    /**
     * ログアウトし、ローカル状態を未認証にする。サーバ側のセッション破棄は送るだけで完了を待たない
     * （送信が失敗してもローカルのトークン・状態は必ず落とす。iOS `try?` 相当のベストエフォート）。
     *
     * 順序（ADR-104 決定 1・14）: [sessionLock] の中でトークンを捕捉 → 破棄 → `Unauthenticated` → lock の外で離脱の通知
     * （[onSubjectLeave]。後始末の起動で、完了は待たない）→ 捕捉したトークンでサーバへ送信（[internalScope]。
     * await しない）。捕捉したトークンが無ければ送らない。`Unknown` / `Unauthenticated` からの logout も同じ手順で、
     * 離脱は起動せず、`Unknown` からなら [onSubjectResolved] を `null` で 1 回呼ぶ。
     *
     * android-slice-auth-01（CI-S0-10・SG-S0-7 = (a)）: 同期部分の間は [logoutsInProgress] を立てて [onUnauthorized] を
     * 抑止する。`withContext` に入った直後、減算の `finally` を持つ `try` の直前に [sessionLock] の中で加算する
     * （増加と `try` の間に suspend する地点を挟まない）。cancel されても `finally` が必ず走り、ガードが解放されて
     * 以後の失効通知を捨て続けない（F10）。
     *
     * CI-S0-10 の WHY（経路 B）: サーバがセッションを削除した直後に別の要求が 401 を受け得る。捕捉と破棄を先に済ませ、
     * 破棄後の失効通知はトークンの照合で、破棄前の通知はこのガードで無視する。
     */
    suspend fun logout(): Unit = withContext(dispatcher) {
        synchronized(sessionLock) { logoutsInProgress++ }
        try {
            val (token, transition) = synchronized(sessionLock) {
                val captured = sessionStore.load()
                sessionStore.clear()
                val t = setAuthState(AuthState.Unauthenticated)
                _lastFailure.value = null
                captured to t
            }
            announce(transition, AuthState.Unauthenticated)
            token?.let { captured ->
                internalScope.launch {
                    try {
                        apiClient.logout(captured)
                    } catch (e: ApiException) {
                        // ベストエフォート: 送信に失敗してもローカルの認証状態は落ちている。
                    }
                }
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
     * android-slice-auth-01（CI-S0-3）: [login] と同じ保存失敗の保証を持つ。
     */
    suspend fun completePasskeyLogin(response: LoginResponse): Unit = withContext(dispatcher) {
        establishSession(response)
    }

    /**
     * [login] と [completePasskeyLogin] に共通のセッション確立。save → loginErrorMessage=null → 状態の遷移 →
     * lastFailure=null を 1 つの区間にまとめ（CI-S0-5・T6-6）、離脱の通知と [onAuthenticated] は lock の外で呼ぶ。
     * 別主体への確立（遷移 ④）では、前の主体の後始末を起動するが、その完了は待たない。
     */
    private suspend fun establishSession(response: LoginResponse) {
        val next = AuthState.Authenticated(response.user)
        val transition = synchronized(sessionLock) {
            if (sessionStore.save(response.token)) {
                _loginErrorMessage.value = null
                val t = setAuthState(next)
                _lastFailure.value = null
                t
            } else {
                null
            }
        }
        if (transition == null) {
            // android-contract-16: 保存失敗時は Authenticated へ遷移させない。
            _loginErrorMessage.value = SESSION_SAVE_ERROR_MESSAGE
            return
        }
        announce(transition, next)
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
        val next = AuthState.Authenticated(updatedUser)
        val transition = synchronized(sessionLock) {
            if (_authState.value is AuthState.Authenticated) setAuthState(next) else null
        }
        transition?.let { announce(it, next) }
    }

    private companion object {
        const val GENERIC_LOGIN_ERROR_MESSAGE = "ログインに失敗しました。接続設定を確認してください"

        /** android-contract-16・CI-S0-3: SessionStore.save 失敗時の文言（spec §3 wording）。 */
        const val SESSION_SAVE_ERROR_MESSAGE = "ログイン状態を端末に保存できませんでした。もう一度お試しください"
    }
}
