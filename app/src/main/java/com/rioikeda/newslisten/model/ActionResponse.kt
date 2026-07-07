package com.rioikeda.newslisten.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * star/dismiss アクションレスポンス DTO。
 *
 * 正本: backend/api/schemas.py:161-167（ActionResponse）。
 * remaining は日次生成クォータ消費後残数。非消費経路・無制限時は None
 * （旧クライアント互換のため常に optional・default None、issue #164・ADR-061）。
 */
@Serializable
data class ActionResponse(
    val status: String,
    @SerialName("article_id") val articleId: String,
    val remaining: Int? = null,
)
