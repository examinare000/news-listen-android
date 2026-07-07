package com.rioikeda.newslisten.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GET /auth/sessions のセッション項目 DTO。
 *
 * 正本: backend/api/schemas.py（SessionResponse）。device_label / last_used_at は
 * null になり得る（未設定デバイスラベル・未使用セッション）。
 */
@Serializable
data class SessionResponse(
    val id: String,
    @SerialName("device_label") val deviceLabel: String?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_used_at") val lastUsedAt: String?,
    val current: Boolean,
)
