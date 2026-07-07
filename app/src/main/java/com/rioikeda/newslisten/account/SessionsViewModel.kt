package com.rioikeda.newslisten.account

import com.rioikeda.newslisten.model.SessionResponse
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.ApiException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * ログイン中のデバイス（セッション）一覧・個別/一括失効の状態とロジックを担う ViewModel
 * （フェーズ11 P11 T4・issue #84 相当）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Sessions/SessionsViewModel.swift のミラー。
 * androidx.lifecycle.ViewModel は継承しない（[com.rioikeda.newslisten.auth.AuthViewModel] と
 * 同じ設計判断）。current フラグはサーバ算出値をそのまま使い、クライアント側でトークン照合は
 * 行わない（着手前反証ゲートで確認済み）。
 */
class SessionsViewModel(
    private val apiClient: ApiClient,
    private val dispatcher: CoroutineDispatcher,
) {
    private val _sessions = MutableStateFlow<List<SessionResponse>>(emptyList())
    val sessions: StateFlow<List<SessionResponse>> = _sessions.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** 一括失効した件数のフィードバック（iOS SessionsViewModel.swift:20 のミラー）。 */
    private val _revokedOthersCount = MutableStateFlow<Int?>(null)
    val revokedOthersCount: StateFlow<Int?> = _revokedOthersCount.asStateFlow()

    /**
     * 現在のデバイス以外にログイン中セッションがあるか（AccountSettingsView.swift:161 の
     * `sessions.filter { !$0.current }.isEmpty` 判定を UI 側で再計算せずに済むよう ViewModel が
     * 導出値として公開する）。UI は「他のデバイスからログアウト」ボタンの disabled 判定に使う。
     */
    private val _hasOtherSessions = MutableStateFlow(false)
    val hasOtherSessions: StateFlow<Boolean> = _hasOtherSessions.asStateFlow()

    /** [_sessions] と、そこから導出される [_hasOtherSessions] を同時に更新する内部ヘルパー。 */
    private fun applySessions(list: List<SessionResponse>) {
        _sessions.value = list
        _hasOtherSessions.value = list.any { !it.current }
    }

    /** 有効セッション一覧をサーバから取得する。 */
    suspend fun loadSessions(): Unit = withContext(dispatcher) {
        _errorMessage.value = null
        _revokedOthersCount.value = null // 再読み込み時に一括失効のフィードバックを消す（iOS:32 準拠）。
        fetchAndApplySessions()
    }

    /**
     * 指定セッションを個別失効する。
     *
     * WHY current ガード無し: iOS SessionsViewModel.swift:48-63 に current チェックは無く、
     * 失効ボタンの非表示（AccountSettingsView.swift:194 `if !session.current`）という UI 側の
     * 導線のみで防いでいる。Android もこれに準拠し、ViewModel 層では current かどうかに
     * 関わらず失効を実行する（UI 側の導線設計は別タスクのスコープ）。
     *
     * 404（既に失効済み）は冪等成功として扱う（backend/API 契約準拠）。これは [ApiClient.revokeSession]
     * で既に処理されるため、ViewModel では 404 を個別に処理する必要はない（ApiClient 層の保証）。
     * 成功時はサーバ再取得ではなくローカル除去のみ行う（iOS:56 と同じ、不要な往復を避ける）。
     */
    suspend fun revokeSession(id: String): Unit = withContext(dispatcher) {
        _revokedOthersCount.value = null // 個別操作時は一括失効のフィードバックを消す（iOS:53 準拠）。
        try {
            apiClient.revokeSession(id)
            applySessions(_sessions.value.filterNot { it.id == id })
        } catch (e: ApiException) {
            _errorMessage.value = REVOKE_ERROR_MESSAGE
        }
    }

    /**
     * 現在以外のセッションを一括失効し（「他のデバイスからログアウト」）、成功したら一覧を再取得する。
     *
     * WHY [loadSessions] を呼ばない: [loadSessions] は再読み込みのたびに [_revokedOthersCount] を
     * nil に戻す（iOS SessionsViewModel.swift:32 準拠）。iOS の `revokeOthers()` はその直後に
     * `await loadSessions()` を呼ぶため、直前で設定した revokedOthersCount が同一の非同期関数内で
     * 即座に打ち消され、UI に表示される前に消えてしまう（SwiftUI は同一実行区間の複数回の
     * @Published 更新を最後の値だけ描画に反映するため）。この一括失効の成功フィードバック自体が
     * この関数の目的であるため、Android では再取得専用の [fetchAndApplySessions] のみを呼び、
     * revokedOthersCount は保持したまま一覧だけを更新する。
     */
    suspend fun revokeOtherSessions(): Unit = withContext(dispatcher) {
        try {
            val response = apiClient.revokeOtherSessions()
            _revokedOthersCount.value = response.revokedCount
            fetchAndApplySessions()
        } catch (e: ApiException) {
            _errorMessage.value = REVOKE_OTHERS_ERROR_MESSAGE
        }
    }

    /** セッション一覧を取得して [applySessions] に反映する共通処理（成否に関わらず isLoading を管理）。 */
    private suspend fun fetchAndApplySessions() {
        _isLoading.value = true
        try {
            applySessions(apiClient.listSessions().sessions)
        } catch (e: ApiException) {
            _errorMessage.value = LIST_ERROR_MESSAGE
        }
        _isLoading.value = false
    }

    private companion object {
        const val LIST_ERROR_MESSAGE = "デバイス一覧の取得に失敗しました"
        const val REVOKE_ERROR_MESSAGE = "ログアウトに失敗しました"
        const val REVOKE_OTHERS_ERROR_MESSAGE = "一括ログアウトに失敗しました"
    }
}
