package com.rioikeda.newslisten.passkey

import com.rioikeda.newslisten.model.PasskeyCredential
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.ApiException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 設定画面のパスキー一覧・削除の状態とロジックを担う ViewModel（フェーズ17 P17・issue #140）。
 *
 * 正本: backend/api/routers/passkey.py の GET /auth/passkey/credentials・
 * DELETE /auth/passkey/credentials/{id}。androidx.lifecycle.ViewModel は継承しない
 * （[com.rioikeda.newslisten.account.SessionsViewModel] と同じ設計判断・同型の一覧+削除構造）。
 */
class PasskeyCredentialsViewModel(
    private val apiClient: ApiClient,
    private val dispatcher: CoroutineDispatcher,
) {
    private val _credentials = MutableStateFlow<List<PasskeyCredential>>(emptyList())
    val credentials: StateFlow<List<PasskeyCredential>> = _credentials.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** パスキー一覧をサーバから取得する。 */
    suspend fun loadCredentials(): Unit = withContext(dispatcher) {
        _isLoading.value = true
        try {
            _credentials.value = apiClient.listPasskeyCredentials().credentials
            _errorMessage.value = null
        } catch (e: ApiException) {
            _errorMessage.value = LIST_ERROR_MESSAGE
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * 指定パスキーを削除する。成功時はサーバ再取得ではなくローカル除去のみ行う
     * （[com.rioikeda.newslisten.account.SessionsViewModel.revokeSession] と同じ、
     * 不要な往復を避ける方針）。
     */
    suspend fun deleteCredential(credentialId: String): Unit = withContext(dispatcher) {
        try {
            apiClient.deletePasskeyCredential(credentialId)
            _credentials.value = _credentials.value.filterNot { it.credentialId == credentialId }
        } catch (e: ApiException) {
            _errorMessage.value = DELETE_ERROR_MESSAGE
        }
    }

    private companion object {
        const val LIST_ERROR_MESSAGE = "パスキー一覧の取得に失敗しました"
        const val DELETE_ERROR_MESSAGE = "パスキーの削除に失敗しました"
    }
}
