package com.rioikeda.newslisten.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GET /auth/passkey/credentials の一覧要素 DTO。
 *
 * 正本: backend/api/routers/passkey.py:62-72（PasskeyCredentialResponse）。public_key は
 * backend が既に除外して返すため、この DTO にも含めない。
 */
@Serializable
data class PasskeyCredential(
    @SerialName("credential_id") val credentialId: String,
    val username: String,
    val name: String?,
    val transports: List<String>,
    val aaguid: String?,
    @SerialName("sign_count") val signCount: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_used_at") val lastUsedAt: String?,
)
