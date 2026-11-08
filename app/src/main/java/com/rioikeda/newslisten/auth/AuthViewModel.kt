package com.rioikeda.newslisten.auth

import com.rioikeda.newslisten.auth.app.AuthSession
import com.rioikeda.newslisten.auth.domain.IssuedSession
import com.rioikeda.newslisten.auth.domain.LoginOutcome
import com.rioikeda.newslisten.core.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ログイン画面の入力の可否と文言を担う ViewModel（フェーズ3）。認証状態の遷移・失効・離脱は [AuthSession] が持ち、
 * ここは空入力の検査、[LoginOutcome] から文言への写し、[AuthSession] への中継だけを行う（android-dec-09・android-dec-86）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Auth/LoginViewModel.swift（ログインのエラー文言）のミラー。
 *
 * androidx.lifecycle.ViewModel は継承しない。Dispatcher を持つ [AuthSession] をコンストラクタ注入し、
 * テストで差し替えられるようにする。呼び出し元（Compose 側）は `viewModelScope.launch { }` から
 * 各 suspend 関数を呼び出す想定。
 */
class AuthViewModel(private val authSession: AuthSession) {
    val authState: StateFlow<AuthState> = authSession.authState

    val lastFailure: StateFlow<ApiException?> = authSession.lastFailure

    private val _loginErrorMessage = MutableStateFlow<String?>(null)
    val loginErrorMessage: StateFlow<String?> = _loginErrorMessage.asStateFlow()

    suspend fun refreshAuth() = authSession.refreshAuth()

    fun retryRefreshAuth() = authSession.retryRefreshAuth()

    /**
     * ログインを実行する。失敗は [loginErrorMessage] に文言を設定する。
     *
     * ユーザー存在を露出しない汎用文言に丸める方針は iOS LoginViewModel.swift 準拠
     * （不一致のみ専用文言、429 含むそれ以外は汎用文言）。
     */
    suspend fun login(username: String, password: String) {
        val trimmedUsername = username.trim()
        if (trimmedUsername.isEmpty() || password.isEmpty()) {
            _loginErrorMessage.value = "ユーザーIDとパスワードを入力してください"
            return
        }
        _loginErrorMessage.value = messageFor(authSession.login(trimmedUsername, password))
    }

    suspend fun logout() = authSession.logout()

    /** パスキーログイン成功後のセッション確立。保存失敗は [login] と同じ文言で伝える。 */
    suspend fun completePasskeyLogin(session: IssuedSession) {
        _loginErrorMessage.value = messageFor(authSession.completePasskeyLogin(session))
    }

    fun applyProfileUpdate(displayName: String) = authSession.applyProfileUpdate(displayName)

    private fun messageFor(outcome: LoginOutcome): String? =
        when (outcome) {
            LoginOutcome.Established -> null
            LoginOutcome.InvalidCredentials -> "ユーザーIDまたはパスワードが正しくありません"
            LoginOutcome.SaveFailed -> "ログイン状態を端末に保存できませんでした。もう一度お試しください"
            is LoginOutcome.Failed -> "ログインに失敗しました。接続設定を確認してください"
        }
}
