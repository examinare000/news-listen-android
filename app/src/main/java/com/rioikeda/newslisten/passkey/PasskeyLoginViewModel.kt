package com.rioikeda.newslisten.passkey

import com.rioikeda.newslisten.model.LoginResponse
import com.rioikeda.newslisten.model.NewsListenJson
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.ApiException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject

/**
 * ログイン画面「パスキーでログイン」フローの状態とロジックを担う ViewModel
 * （フェーズ17 P17・issue #140）。
 *
 * 正本: backend/api/routers/passkey.py の login/options（discoverable credential、
 * username 不要）・login/verify。androidx.lifecycle.ViewModel は継承しない
 * （[com.rioikeda.newslisten.auth.AuthViewModel] と同じ設計判断）。
 *
 * WHY [onLoginSuccess] を注入する（AuthViewModel を直接持たない）: セッション確立
 * （sessionStore.save・authState 遷移）は auth 層（[com.rioikeda.newslisten.auth.AuthViewModel]）が
 * 正本という既存の責務分担を保つため、verify 成功で得た [LoginResponse] を渡すだけの
 * フック注入パターン（[com.rioikeda.newslisten.auth.AuthViewModel] の onLogoutCleanup/
 * onAuthenticated と同型）にした。AppContainer が
 * `{ response -> authViewModel.completePasskeyLogin(response) }` を注入する。
 */
class PasskeyLoginViewModel(
    private val apiClient: ApiClient,
    private val passkeyProvider: PasskeyProvider,
    private val dispatcher: CoroutineDispatcher,
    private val onLoginSuccess: suspend (LoginResponse) -> Unit = {},
) {
    private val _isLoggingIn = MutableStateFlow(false)
    val isLoggingIn: StateFlow<Boolean> = _isLoggingIn.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * パスキーログインセレモニーを実行する。
     *
     * ユーザーキャンセル（[PasskeyCancellationException]）は失敗として扱わず、
     * エラーメッセージを表示しない（done 基準・issue #140 P17 要件）。
     */
    suspend fun loginWithPasskey(): Unit = withContext(dispatcher) {
        _errorMessage.value = null
        _isLoggingIn.value = true
        try {
            // username は常に null（discoverable credential フロー。backend コメント参照:
            // ユーザー列挙 oracle 防止のため username は credential 列挙に使われない）。
            val options = apiClient.passkeyLoginOptions(null)
            val responseJson = passkeyProvider.getCredential(options.options)
            val credential = NewsListenJson.parseToJsonElement(responseJson).jsonObject
            val loginResponse = apiClient.passkeyLoginVerify(options.challengeId, credential)
            onLoginSuccess(loginResponse)
        } catch (e: PasskeyCancellationException) {
            // ユーザーがシステム UI をキャンセルしただけなので何もしない（エラー表示しない）。
        } catch (e: ApiException) {
            _errorMessage.value = GENERIC_MESSAGE
        } catch (e: PasskeyProviderException) {
            _errorMessage.value = GENERIC_MESSAGE
        } finally {
            _isLoggingIn.value = false
        }
    }

    private companion object {
        const val GENERIC_MESSAGE = "パスキーでのログインに失敗しました"
    }
}
