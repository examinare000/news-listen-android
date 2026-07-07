package com.rioikeda.newslisten.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DeviceTokenRequest（POST /notifications/device-tokens の body）の検証。
 *
 * 正本: backend/api/routers/notifications.py の `_DeviceTokenRequest`。
 * platform フィールドは backend で処理される（android は FCM 形式バリデーション）。
 */
class DeviceTokenRequestTest {

    @Test
    fun deviceTokenとplatformがsnake_caseでエンコードされる() {
        val request = DeviceTokenRequest(deviceToken = "token-abc", platform = "android")

        val encoded = NewsListenJson.encodeToString(DeviceTokenRequest.serializer(), request)

        assertEquals("""{"device_token":"token-abc","platform":"android"}""", encoded)
    }
}
