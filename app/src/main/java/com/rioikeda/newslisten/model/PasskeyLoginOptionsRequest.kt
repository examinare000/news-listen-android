package com.rioikeda.newslisten.model

import kotlinx.serialization.Serializable

/**
 * POST /auth/passkey/login/options のリクエストボディ。
 *
 * 正本: backend/api/routers/passkey.py:58-59（PasskeyLoginOptionsRequest）。username は
 * discoverable credential フロー前提のため backend は credential 列挙に使わない（ユーザー列挙
 * oracle 防止のコメント参照）が、スキーマ互換のため任意項目として保持する。未指定時は
 * NewsListenJson の既定（encodeDefaults=false）により省略される。
 */
@Serializable
data class PasskeyLoginOptionsRequest(
    val username: String? = null,
)
