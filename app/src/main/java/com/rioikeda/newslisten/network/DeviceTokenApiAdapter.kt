package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.notification.app.DeviceTokenApi

/** [ApiClient.registerDeviceToken] を包む。例外は呼び手へそのまま伝える。 */
internal class DeviceTokenApiAdapter(private val apiClient: ApiClient) : DeviceTokenApi {
    override suspend fun register(token: String, platform: String) {
        apiClient.registerDeviceToken(token, platform)
    }
}
