package com.rioikeda.newslisten.model

import kotlinx.serialization.Serializable

/**
 * ログインリクエスト DTO。
 *
 * 正本: backend/api/schemas.py:233-235（LoginRequest）。
 */
@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
) {
    /**
     * toStringをオーバーライドして password を[REDACTED]にマスクする。
     * デバッグログやException::toString()で平文のパスワードが出力されるのを防ぐ。
     */
    override fun toString(): String =
        "LoginRequest(username=$username, password=[REDACTED])"
}
