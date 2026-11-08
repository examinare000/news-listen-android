package com.rioikeda.newslisten.passkey

import com.rioikeda.newslisten.passkey.app.PasskeyCommands
import com.rioikeda.newslisten.passkey.app.PasskeyRegisterOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 設定画面「パスキーを追加」フローの状態とロジックを担う ViewModel（フェーズ17 P17・issue #140）。
 *
 * 正本: backend/api/routers/passkey.py の register/options・register/verify。
 * androidx.lifecycle.ViewModel は継承しない（[com.rioikeda.newslisten.auth.AuthViewModel] と
 * 同じ設計判断）。セレモニーの手順と登録後の一覧の更新は [PasskeyCommands] が持つ。
 * ここは結果から文言を選ぶだけ。
 */
class PasskeyRegistrationViewModel(
    private val passkeyCommands: PasskeyCommands,
    private val dispatcher: CoroutineDispatcher,
) {
    private val _isRegistering = MutableStateFlow(false)
    val isRegistering: StateFlow<Boolean> = _isRegistering.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * パスキー登録セレモニーを実行する。
     *
     * ユーザーキャンセル（[PasskeyCancellationException]）は失敗として扱わず、
     * エラーメッセージを表示しない（done 基準・issue #140 P17 要件）。
     */
    suspend fun register(): Unit = withContext(dispatcher) {
        _errorMessage.value = null
        _isRegistering.value = true
        try {
            when (passkeyCommands.registerPasskey()) {
                // ユーザーがシステム UI をキャンセルしただけなので何も出さない。
                PasskeyRegisterOutcome.Registered, PasskeyRegisterOutcome.Cancelled -> Unit
                PasskeyRegisterOutcome.Conflict -> _errorMessage.value = CONFLICT_MESSAGE
                is PasskeyRegisterOutcome.Failed, is PasskeyRegisterOutcome.ProviderFailed ->
                    _errorMessage.value = GENERIC_MESSAGE
            }
        } finally {
            _isRegistering.value = false
        }
    }

    private companion object {
        const val GENERIC_MESSAGE = "パスキーの登録に失敗しました"
        const val CONFLICT_MESSAGE = "このパスキーは既に登録されています"
    }
}
