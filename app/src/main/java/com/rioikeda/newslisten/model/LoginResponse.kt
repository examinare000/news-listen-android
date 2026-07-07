package com.rioikeda.newslisten.model

import kotlinx.serialization.Serializable

/**
 * ログイン成功時のレスポンス DTO。
 *
 * 正本: backend/api/schemas.py:246-254（LoginResponse）。
 * token フィールド（access_token ではない）。iOS など Cookie を使わないクライアント向け。
 */
@Serializable
data class LoginResponse(
    val token: String,
    val user: UserResponse,
) {
    /**
     * toStringをオーバーライドして token を[REDACTED]にマスクする。
     * デバッグログやException::toString()で平文のセッショントークンが出力されるのを防ぐ。
     */
    override fun toString(): String =
        "LoginResponse(token=[REDACTED], user=$user)"
}
