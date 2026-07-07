package com.rioikeda.newslisten.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * PUT /settings/preferences のリクエストボディ DTO（部分更新）。
 *
 * 正本: backend/api/schemas.py:411-417（UpdatePreferencesRequest）。指定フィールドのみ
 * 変更し他は保持する backend の model_copy(update=exclude_none) 契約に対応するため、
 * 未指定（null）フィールドは [NewsListenJson] の encodeDefaults=false によりエンコード時に
 * 省略される。ApiClient.updatePreferences は difficulty/speed の2引数のみを公開し
 * （iOS APIClient.swift:151 準拠）、digest 系は今回の UI スコープ外だが DTO としては
 * backend の全フィールドを optional で保持する。
 */
@Serializable
data class UpdatePreferencesRequest(
    @SerialName("default_difficulty") val defaultDifficulty: String? = null,
    @SerialName("default_playback_speed") val defaultPlaybackSpeed: Double? = null,
    @SerialName("digest_enabled") val digestEnabled: Boolean? = null,
    @SerialName("digest_article_count") val digestArticleCount: Int? = null,
)
