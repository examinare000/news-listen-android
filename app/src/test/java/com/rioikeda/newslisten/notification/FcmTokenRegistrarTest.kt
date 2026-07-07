package com.rioikeda.newslisten.notification

import com.rioikeda.newslisten.network.ApiException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FcmTokenRegistrar] の挙動検証（フェーズ9・プッシュ通知）。
 *
 * 正本: backend/api/routers/notifications.py の POST/DELETE /notifications/device-tokens。
 * 通知権限（POST_NOTIFICATIONS）は Android 固有の OS レベル opt-in のため、許可済みの
 * ときのみ登録を試みる（web の明示ボタン opt-in、iOS 未配線とは異なる設計判断）。
 */
class FcmTokenRegistrarTest {

    /** テスト対象の生成を共通化する。dispatcher は runTest の testScheduler に紐付ける。 */
    private fun TestScope.newRegistrar(
        apiClient: FakeNotificationApiClient = FakeNotificationApiClient(),
        tokenProvider: suspend () -> String? = { "fcm-token" },
        permissionChecker: () -> Boolean = { true },
    ): FcmTokenRegistrar =
        FcmTokenRegistrar(apiClient, tokenProvider, permissionChecker, StandardTestDispatcher(testScheduler))

    // --- onAuthenticated ---

    @Test
    fun 許可済みで認証されたらトークンを取得し登録する() = runTest {
        val apiClient = FakeNotificationApiClient()
        val registrar = newRegistrar(apiClient = apiClient, tokenProvider = { "fcm-token-abc" })

        registrar.onAuthenticated()

        assertEquals(listOf("fcm-token-abc" to "android"), apiClient.registerCalls)
    }

    @Test
    fun 通知権限が未許可なら登録を試みない() = runTest {
        val apiClient = FakeNotificationApiClient()
        var tokenProviderCalled = false
        val registrar = newRegistrar(
            apiClient = apiClient,
            tokenProvider = { tokenProviderCalled = true; "fcm-token-abc" },
            permissionChecker = { false },
        )

        registrar.onAuthenticated()

        assertFalse(tokenProviderCalled)
        assertTrue(apiClient.registerCalls.isEmpty())
    }

    @Test
    fun トークン取得できなければ登録しない() = runTest {
        val apiClient = FakeNotificationApiClient()
        val registrar = newRegistrar(apiClient = apiClient, tokenProvider = { null })

        registrar.onAuthenticated()

        assertTrue(apiClient.registerCalls.isEmpty())
    }

    @Test
    fun 登録失敗は握って例外を投げない() = runTest {
        val apiClient = FakeNotificationApiClient(
            onRegisterDeviceToken = { _, _ -> throw ApiException.HttpError(422) },
        )
        val registrar = newRegistrar(apiClient = apiClient)

        // 例外が伝播しなければ成功（backend 未対応の 404/422 等いずれも握って続行する仕様）。
        registrar.onAuthenticated()
    }

    @Test
    fun トークン取得自体が例外を投げても握る() = runTest {
        val registrar = newRegistrar(tokenProvider = { error("firebase unavailable") })

        registrar.onAuthenticated()
    }

    // --- onLogout ---

    @Test
    fun ログアウト時トークン取得できれば解除する() = runTest {
        val apiClient = FakeNotificationApiClient()
        val registrar = newRegistrar(apiClient = apiClient, tokenProvider = { "fcm-token-abc" })

        registrar.onLogout()

        assertEquals(listOf("fcm-token-abc" to "android"), apiClient.unregisterCalls)
    }

    @Test
    fun ログアウト時トークン取得できなければ解除を試みない() = runTest {
        val apiClient = FakeNotificationApiClient()
        val registrar = newRegistrar(apiClient = apiClient, tokenProvider = { null })

        registrar.onLogout()

        assertTrue(apiClient.unregisterCalls.isEmpty())
    }

    @Test
    fun ログアウト時解除失敗は握って例外を投げない() = runTest {
        val apiClient = FakeNotificationApiClient(
            onUnregisterDeviceToken = { _, _ -> throw ApiException.NetworkError(RuntimeException("offline")) },
        )
        val registrar = newRegistrar(apiClient = apiClient)

        registrar.onLogout()
    }

    // --- onNewToken ---

    @Test
    fun 認証済みかつ許可済みならonNewTokenで再登録する() = runTest {
        val apiClient = FakeNotificationApiClient()
        val registrar = newRegistrar(apiClient = apiClient)
        registrar.onAuthenticated()

        registrar.onNewToken("fcm-token-new")

        assertEquals(
            listOf("fcm-token" to "android", "fcm-token-new" to "android"),
            apiClient.registerCalls,
        )
    }

    @Test
    fun 未認証状態でのonNewTokenは登録しない() = runTest {
        val apiClient = FakeNotificationApiClient()
        val registrar = newRegistrar(apiClient = apiClient)

        registrar.onNewToken("fcm-token-new")

        assertTrue(apiClient.registerCalls.isEmpty())
    }

    @Test
    fun ログアウト後のonNewTokenは登録しない() = runTest {
        val apiClient = FakeNotificationApiClient()
        val registrar = newRegistrar(apiClient = apiClient)
        registrar.onAuthenticated()
        registrar.onLogout()

        registrar.onNewToken("fcm-token-new")

        assertEquals(listOf("fcm-token" to "android"), apiClient.registerCalls)
    }

    @Test
    fun 認証済みでも通知権限が未許可ならonNewTokenは登録しない() = runTest {
        val apiClient = FakeNotificationApiClient()
        var permissionGranted = true
        val registrar = newRegistrar(apiClient = apiClient, permissionChecker = { permissionGranted })
        registrar.onAuthenticated()
        permissionGranted = false

        registrar.onNewToken("fcm-token-new")

        assertEquals(listOf("fcm-token" to "android"), apiClient.registerCalls)
    }
}
