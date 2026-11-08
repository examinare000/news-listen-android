package com.rioikeda.newslisten.passkey

import com.rioikeda.newslisten.passkey.app.PasskeyCommands
import com.rioikeda.newslisten.passkey.app.PasskeyLoginOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * ログイン画面「パスキーでログイン」フローの状態とロジックを担う ViewModel
 * （フェーズ17 P17・issue #140）。
 *
 * 正本: backend/api/routers/passkey.py の login/options（discoverable credential、
 * username 不要）・login/verify。androidx.lifecycle.ViewModel は継承しない
 * （[com.rioikeda.newslisten.auth.AuthViewModel] と同じ設計判断）。
 *
 * セレモニーの手順とセッション確立への引き渡しは [PasskeyCommands] が持つ。ここは結果から文言を選ぶだけ。
 */
class PasskeyLoginViewModel(
    private val passkeyCommands: PasskeyCommands,
    private val dispatcher: CoroutineDispatcher,
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
            when (passkeyCommands.loginWithPasskey()) {
                // ユーザーがシステム UI をキャンセルしただけなので何も出さない。
                PasskeyLoginOutcome.LoggedIn, PasskeyLoginOutcome.Cancelled -> Unit
                is PasskeyLoginOutcome.Failed, is PasskeyLoginOutcome.ProviderFailed ->
                    _errorMessage.value = GENERIC_MESSAGE
            }
        } finally {
            _isLoggingIn.value = false
        }
    }

    private companion object {
        const val GENERIC_MESSAGE = "パスキーでのログインに失敗しました"
    }
}
