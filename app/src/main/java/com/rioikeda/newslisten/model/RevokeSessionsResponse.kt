package com.rioikeda.newslisten.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST /auth/sessions/revoke-others のレスポンス DTO。
 *
 * 正本: backend/api/schemas.py（RevokeSessionsResponse）。
 */
@Serializable
data class RevokeSessionsResponse(
    @SerialName("revoked_count") val revokedCount: Int,
)
