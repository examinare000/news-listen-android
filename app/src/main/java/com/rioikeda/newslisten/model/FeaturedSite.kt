package com.rioikeda.newslisten.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * システム提供のおすすめサイト1件。
 *
 * 正本: backend/api/schemas.py:182-189（FeaturedSiteResponse）。
 */
@Serializable
data class FeaturedSite(
    val id: String,
    val name: String,
    val url: String,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    val description: String? = null,
    val order: Int = 0,
)

/**
 * GET /settings/featured-sources のレスポンス。
 *
 * 正本: backend/api/schemas.py:191-193（FeaturedSitesResponse）。
 */
@Serializable
data class FeaturedSitesResponse(
    val sites: List<FeaturedSite>,
)
