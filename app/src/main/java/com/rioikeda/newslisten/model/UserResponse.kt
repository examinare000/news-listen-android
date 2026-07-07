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
)
