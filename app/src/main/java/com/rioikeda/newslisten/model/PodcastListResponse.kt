package com.rioikeda.newslisten.model

import kotlinx.serialization.Serializable

/**
 * ポッドキャスト一覧レスポンス DTO。
 *
 * 正本: backend/api/schemas.py:98-100（PodcastListResponse）。
 */
@Serializable
data class PodcastListResponse(
    val podcasts: List<PodcastResponse>,
)
