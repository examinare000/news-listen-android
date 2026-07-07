package com.rioikeda.newslisten.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST /auth/password のリクエスト DTO。
 *
 * 正本: backend/api/schemas.py（PasswordChangeRequest）。
 * 現パスワード誤りは 400、新パスワード強度不足は 422 で backend が区別して返す
 * （OkHttpApiClient.changePassword 側は ApiException.HttpError.code をそのまま伝播し、
 * 呼び出し側で 400/422 を判別する）。
 */
@Serializable
data class PasswordChangeRequest(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String,
) {
    /**
     * toStringをオーバーライドして両パスワードを[REDACTED]にマスクする（LoginRequest と同じ方針）。
     */
    override fun toString(): String =
        "PasswordChangeRequest(currentPassword=[REDACTED], newPassword=[REDACTED])"
}
