package com.rioikeda.newslisten.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * PATCH /auth/me のリクエスト DTO。
 *
 * 正本: backend/api/schemas.py（ProfileUpdateRequest。display_name は min1/max64）。
 */
@Serializable
data class ProfileUpdateRequest(
    @SerialName("display_name") val displayName: String,
)
