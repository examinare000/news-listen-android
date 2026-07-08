package com.rioikeda.newslisten.model

import kotlinx.serialization.Serializable

/**
 * GET /auth/sessions のレスポンス DTO。
 *
 * 正本: backend/api/schemas.py（SessionsListResponse）。
 */
@Serializable
data class SessionsListResponse(
    val sessions: List<SessionResponse>,
)
