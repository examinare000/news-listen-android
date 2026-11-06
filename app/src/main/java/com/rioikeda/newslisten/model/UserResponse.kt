package com.rioikeda.newslisten.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ユーザー公開情報 DTO。password_hash は決して含まれない。
 *
 * 正本: backend/api/schemas.py:238-243（UserResponse）。
 */
@Serializable
data class UserResponse(
    val username: String,
    val role: String,
    @SerialName("display_name") val displayName: String,
    /** 端末ローカル資産の持ち主を識別する id。欠落しても decode は成功する（ADR-104 決定 16）。 */
    @SerialName("user_id") val userId: String? = null,
)
