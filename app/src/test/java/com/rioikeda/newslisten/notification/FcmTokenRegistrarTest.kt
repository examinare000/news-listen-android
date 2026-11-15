package com.rioikeda.newslisten.notification

import com.rioikeda.newslisten.core.ApiException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        deviceTokenApi: FakeDeviceTokenApi = FakeDeviceTokenApi(),
        pushTokenSource: FakePushTokenSource = FakePushTokenSource(),
        notificationPermission: FakeNotificationPermission = FakeNotificationPermission(),
    ): FcmTokenRegistrar =
        FcmTokenRegistrar(deviceTokenApi, pushTokenSource, notificationPermission, StandardTestDispatcher(testScheduler))

    // --- onAuthenticated ---

    @Test
    fun 許可済みで認証されたらトークンを取得し登録する() = runTest {
        val api = FakeDeviceTokenApi()
        val registrar = newRegistrar(deviceTokenApi = api, pushTokenSource = FakePushTokenSource { "fcm-token-abc" })

        registrar.onAuthenticated()

        assertEquals(listOf("fcm-token-abc" to "android"), api.registerCalls)
    }

    @Test
    fun 通知権限が未許可なら登録を試みない() = runTest {
        val api = FakeDeviceTokenApi()
        val source = FakePushTokenSource { "fcm-token-abc" }
        val registrar = newRegistrar(
            deviceTokenApi = api,
            pushTokenSource = source,
            notificationPermission = FakeNotificationPermission(granted = false),
        )

        registrar.onAuthenticated()

        assertEquals(0, source.callCount)
        assertTrue(api.registerCalls.isEmpty())
    }

    @Test
    fun トークン取得できなければ登録しない() = runTest {
        val api = FakeDeviceTokenApi()
        val registrar = newRegistrar(deviceTokenApi = api, pushTokenSource = FakePushTokenSource { null })

        registrar.onAuthenticated()

        assertTrue(api.registerCalls.isEmpty())
    }

    @Test
    fun 登録失敗は握って例外を投げない() = runTest {
        val api = FakeDeviceTokenApi { _, _ -> throw ApiException.HttpError(422) }
        val registrar = newRegistrar(deviceTokenApi = api)

        // 例外が伝播しなければ成功（backend 未対応の 404/422 等いずれも握って続行する仕様）。
        registrar.onAuthenticated()
    }

    @Test
    fun トークン取得自体が例外を投げても握る() = runTest {
        val registrar = newRegistrar(pushTokenSource = FakePushTokenSource { error("firebase unavailable") })

        registrar.onAuthenticated()
    }

    // --- onLogout ---

    @Test
    fun `android-dec-01 ログアウト時はトークンを取得せずAPIも呼ばない`() = runTest {
        // verifies: android-dec-01
        val api = FakeDeviceTokenApi()
        val source = FakePushTokenSource { "fcm-token-abc" }
        val registrar = newRegistrar(deviceTokenApi = api, pushTokenSource = source)

        registrar.onLogout()

        assertEquals(0, source.callCount)
        assertTrue(api.registerCalls.isEmpty())
    }

    // --- onNewToken ---

    @Test
    fun 認証済みかつ許可済みならonNewTokenで再登録する() = runTest {
        val api = FakeDeviceTokenApi()
        val registrar = newRegistrar(deviceTokenApi = api)
        registrar.onAuthenticated()

        registrar.onNewToken("fcm-token-new")

        assertEquals(
            listOf("fcm-token" to "android", "fcm-token-new" to "android"),
            api.registerCalls,
        )
    }

    @Test
    fun 未認証状態でのonNewTokenは登録しない() = runTest {
        val api = FakeDeviceTokenApi()
        val registrar = newRegistrar(deviceTokenApi = api)

        registrar.onNewToken("fcm-token-new")

        assertTrue(api.registerCalls.isEmpty())
    }

    @Test
    fun ログアウト後のonNewTokenは登録しない() = runTest {
        val api = FakeDeviceTokenApi()
        val registrar = newRegistrar(deviceTokenApi = api)
        registrar.onAuthenticated()
        registrar.onLogout()

        registrar.onNewToken("fcm-token-new")

        assertEquals(listOf("fcm-token" to "android"), api.registerCalls)
    }

    @Test
    fun 認証済みでも通知権限が未許可ならonNewTokenは登録しない() = runTest {
        val api = FakeDeviceTokenApi()
        val permission = FakeNotificationPermission(granted = true)
        val registrar = newRegistrar(deviceTokenApi = api, notificationPermission = permission)
        registrar.onAuthenticated()
        permission.granted = false

        registrar.onNewToken("fcm-token-new")

        assertEquals(listOf("fcm-token" to "android"), api.registerCalls)
    }
}
