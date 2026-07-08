package com.rioikeda.newslisten.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * POST /auth/passkey/register/verify・POST /auth/passkey/login/verify 共通のリクエストボディ。
 *
 * 正本: backend/api/routers/passkey.py:53-55（PasskeyVerifyRequest）。credential は
 * `dict` を要求するため、Credential Manager が返す registrationResponseJson /
 * authenticationResponseJson（文字列）を呼び出し側で一段パースした [JsonObject] を渡す
 * （生文字列のまま送ると backend 側で 422 になる）。
 */
@Serializable
data class PasskeyVerifyRequest(
    @SerialName("challenge_id") val challengeId: String,
    val credential: JsonObject,
)
