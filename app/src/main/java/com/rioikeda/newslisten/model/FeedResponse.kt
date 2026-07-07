package com.rioikeda.newslisten.model

import kotlinx.serialization.Serializable

/**
 * フィード（記事一覧）レスポンス DTO。
 *
 * 正本: backend/api/schemas.py:42-44（FeedResponse）。
 */
@Serializable
data class FeedResponse(
    val articles: List<ArticleResponse>,
    val date: String,
)
