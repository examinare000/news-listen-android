package com.rioikeda.newslisten.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 記事レスポンス DTO。
 *
 * 正本: backend/api/schemas.py:32-39（ArticleResponse）。フィールドはそこから直接転記する。
 */
@Serializable
data class ArticleResponse(
    val id: String,
    val title: String,
    val url: String,
    val source: String,
    val score: Double,
    @SerialName("published_at") val publishedAt: String,
    @SerialName("is_read") val isRead: Boolean = false,
)
