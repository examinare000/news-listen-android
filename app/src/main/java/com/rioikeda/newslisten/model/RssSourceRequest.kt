package com.rioikeda.newslisten.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * POST /settings/sources のリクエストボディ DTO。
 *
 * 正本: backend/api/schemas.py:106-116（RssSourceRequest）。URL の SSRF 検査は
 * backend 側の責務のため、DTO はプレーンな String のまま転送する。
 */
@Serializable
data class RssSourceCreateRequest(
    val name: String,
    val url: String,
)

/**
 * PUT /settings/sources のリクエストボディ DTO。
 *
 * 正本: backend/api/schemas.py:119-128（RssSourceUpdateRequest）。oldUrl は更新対象を
 * 特定するルックアップキー（DELETE のクエリパラメータと同格）で、name/url とは別に
 * old_url として送る。
 */
@Serializable
data class RssSourceUpdateRequest(
    val name: String,
    val url: String,
    @SerialName("old_url") val oldUrl: String,
)
