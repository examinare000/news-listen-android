package com.rioikeda.newslisten.notification

import com.rioikeda.newslisten.notification.app.DeviceTokenApi
import com.rioikeda.newslisten.notification.app.NotificationPermission
import com.rioikeda.newslisten.notification.app.PushTokenSource

/** 登録の呼出を記録する [DeviceTokenApi] の fake。記録は応答の lambda を呼ぶ前に行う。解除は port に無い。 */
class FakeDeviceTokenApi(
    private val onRegister: suspend (token: String, platform: String) -> Unit = { _, _ -> },
) : DeviceTokenApi {
    /** `register` に渡された (token, platform) の呼出履歴。 */
    val registerCalls: MutableList<Pair<String, String>> = mutableListOf()

    override suspend fun register(token: String, platform: String) {
        registerCalls.add(token to platform)
        onRegister(token, platform)
    }
}

/** token の取得を記録する [PushTokenSource] の fake。 */
class FakePushTokenSource(
    private val onToken: suspend () -> String? = { "fcm-token" },
) : PushTokenSource {
    var callCount: Int = 0
        private set

    override suspend fun token(): String? {
        callCount++
        return onToken()
    }
}

/** 通知権限の fake。[granted] を書き換えると、次に読んだときから変わる（呼ぶたびに評価される契約の確認に使う）。 */
class FakeNotificationPermission(var granted: Boolean = true) : NotificationPermission {
    override val isGranted: Boolean get() = granted
}
