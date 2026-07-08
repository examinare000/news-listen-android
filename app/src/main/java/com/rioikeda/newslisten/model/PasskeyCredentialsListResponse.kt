package com.rioikeda.newslisten.model

import kotlinx.serialization.Serializable

/**
 * GET /auth/passkey/credentials のレスポンス DTO。
 *
 * 正本: backend/api/routers/passkey.py:75-76（PasskeyCredentialsListResponse）。
 */
@Serializable
data class PasskeyCredentialsListResponse(
    val credentials: List<PasskeyCredential>,
)
