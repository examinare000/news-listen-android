package com.rioikeda.newslisten.notification

import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * FCM デバイストークンの登録・解除ロジック（フェーズ9・プッシュ通知）。
 *
 * 正本: backend/api/routers/notifications.py の POST/DELETE /notifications/device-tokens。
 * backend は platform に応じた形式検証を行う（android は FCM トークン用バリデーション）。
 * 登録・解除の失敗は種別（422含む/通信不可等）を問わず握って続行する（ベストエフォート）。
 *
 * WHY 権限ゲート: Android は通知権限（POST_NOTIFICATIONS, API 33+）を OS レベルの
 * オプトインとして扱う。web は明示ボタンの opt-in、iOS は未配線（参照実装なし）——
 * プラットフォームごとに独立した設計判断（ADR 記録予定）。設定画面フェーズで明示トグルを追加予定。
 *
 * WHY isAuthenticated を内部状態として保持する: onNewToken() は FCM SDK からトークン更新の
 * たびに非同期で呼ばれ、呼び出し元は「現在ログイン中か」を都度渡す手段を持たない
 * （FirebaseMessagingService は認証状態を知らない）。そのため onAuthenticated/onLogout の
 * 呼び出しをそのまま「ログイン状態の事実」として記憶し、onNewToken はその内部状態と
 * 都度評価する permissionChecker() の両方で判定する（権限は実行時に変わり得るため毎回再評価）。
 */
class FcmTokenRegistrar(
    private val apiClient: ApiClient,
    private val tokenProvider: suspend () -> String?,
    private val permissionChecker: () -> Boolean,
    private val dispatcher: CoroutineDispatcher,
) {
    private var isAuthenticated = false

    /** 認証確立時に呼ぶ。通知権限が許可済みのときのみ FCM トークンを取得し登録する。 */
    suspend fun onAuthenticated(): Unit = withContext(dispatcher) {
        isAuthenticated = true
        if (!permissionChecker()) return@withContext
        val token = fetchTokenOrNull() ?: return@withContext
        registerToken(token)
    }

    /** ログアウト時に呼ぶ。トークンが取得できれば解除する（ベストエフォート）。 */
    suspend fun onLogout(): Unit = withContext(dispatcher) {
        isAuthenticated = false
        val token = fetchTokenOrNull() ?: return@withContext
        try {
            apiClient.unregisterDeviceToken(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // ベストエフォート: 通信失敗や一時的なサーバエラーでもログアウト自体を妨げない。
        }
    }

    /** FCM トークン更新時に呼ぶ。認証済みかつ通知権限が許可済みのときのみ再登録する。 */
    suspend fun onNewToken(token: String): Unit = withContext(dispatcher) {
        if (!isAuthenticated || !permissionChecker()) return@withContext
        registerToken(token)
    }

    /** [tokenProvider] を安全に呼び出す。取得失敗（Firebase 側の例外含む）は null 扱いで握る。 */
    private suspend fun fetchTokenOrNull(): String? =
        try {
            tokenProvider()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private suspend fun registerToken(token: String) {
        try {
            apiClient.registerDeviceToken(token, PLATFORM_ANDROID)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // ベストエフォート: 通信失敗や一時的なサーバエラーを握り、アプリ動作を妨げない。
        }
    }

    private companion object {
        const val PLATFORM_ANDROID = "android"
    }
}
