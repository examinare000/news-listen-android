package com.rioikeda.newslisten.model

import kotlinx.serialization.Serializable

/**
 * 登録済みの RSS 配信元1件。
 *
 * 正本: backend/shared/models.py:117-119（RssSource）。name/url の2フィールドのみ
 * （id を持たない。url が一意キー）。
 */
@Serializable
data class RssSource(
    val name: String,
    val url: String,
)

/**
 * GET /settings/sources のレスポンス。
 *
 * 正本: backend/api/schemas.py:131-134（RssSourcesResponse）。
 */
@Serializable
data class RssSourcesResponse(
    val sources: List<RssSource>,
)
