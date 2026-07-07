package com.rioikeda.newslisten.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST /notifications/device-tokens のリクエストボディ DTO（フェーズ9・プッシュ通知）。
 *
 * 正本: backend/api/routers/notifications.py の `_DeviceTokenRequest`。
 * backend は platform に応じた形式検証を行う（ios: 16進文字列64〜200桁、android: FCM形式）。
 */
@Serializable
data class DeviceTokenRequest(
    @SerialName("device_token") val deviceToken: String,
    @SerialName("platform") val platform: String,
)
