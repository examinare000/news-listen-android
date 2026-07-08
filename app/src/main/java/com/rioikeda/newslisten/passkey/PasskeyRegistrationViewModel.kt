package com.rioikeda.newslisten.passkey

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
 * 設定画面「パスキーを追加」フローの状態とロジックを担う ViewModel（フェーズ17 P17・issue #140）。
 *
 * 正本: backend/api/routers/passkey.py の register/options・register/verify。
 * androidx.lifecycle.ViewModel は継承しない（[com.rioikeda.newslisten.auth.AuthViewModel] と
 * 同じ設計判断）。Context/CredentialManager の実体には依存せず [PasskeyProvider] 経由で
 * セレモニーを実行する（ADR-066）。
 *
 * @param onRegistered 登録成功時に呼ぶフック（AppContainer が
 * `{ passkeyCredentialsViewModel.loadCredentials() }` を注入し、一覧に反映する）。
 */
class PasskeyRegistrationViewModel(
    private val apiClient: ApiClient,
    private val passkeyProvider: PasskeyProvider,
    private val dispatcher: CoroutineDispatcher,
    private val onRegistered: suspend () -> Unit = {},
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
            val options = apiClient.passkeyRegisterOptions()
            val responseJson = passkeyProvider.createCredential(options.options)
            val credential = NewsListenJson.parseToJsonElement(responseJson).jsonObject
            apiClient.passkeyRegisterVerify(options.challengeId, credential)
            onRegistered()
        } catch (e: PasskeyCancellationException) {
            // ユーザーがシステム UI をキャンセルしただけなので何もしない（エラー表示しない）。
        } catch (e: ApiException.HttpError) {
            _errorMessage.value = if (e.code == 409) CONFLICT_MESSAGE else GENERIC_MESSAGE
        } catch (e: ApiException) {
            _errorMessage.value = GENERIC_MESSAGE
        } catch (e: PasskeyProviderException) {
            _errorMessage.value = GENERIC_MESSAGE
        } finally {
            _isRegistering.value = false
        }
    }

    private companion object {
        const val GENERIC_MESSAGE = "パスキーの登録に失敗しました"
        const val CONFLICT_MESSAGE = "このパスキーは既に登録されています"
    }
}
