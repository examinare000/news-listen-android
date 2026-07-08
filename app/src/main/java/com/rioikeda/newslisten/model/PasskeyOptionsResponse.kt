package com.rioikeda.newslisten.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST /auth/passkey/register/options・POST /auth/passkey/login/options 共通のレスポンス DTO。
 *
 * 正本: backend/api/routers/passkey.py:48-51（PasskeyOptionsResponse）。
 * options は backend の options_to_json() が返す JSON 文字列そのもの（二重デコードしない）。
 * Credential Manager の requestJson へそのまま渡す（呼び出し側の責務。model 層は素通しする）。
 */
@Serializable
data class PasskeyOptionsResponse(
    @SerialName("challenge_id") val challengeId: String,
    val options: String,
)
