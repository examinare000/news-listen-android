package com.rioikeda.newslisten.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ユーザープリファレンス公開情報 DTO。
 *
 * 正本: backend/api/schemas.py:402-408（PreferencesResponse）。4フィールド全てが必須。
 */
@Serializable
data class PreferencesResponse(
    @SerialName("default_difficulty") val defaultDifficulty: String,
    @SerialName("default_playback_speed") val defaultPlaybackSpeed: Double,
    @SerialName("digest_enabled") val digestEnabled: Boolean,
    @SerialName("digest_article_count") val digestArticleCount: Int,
)
