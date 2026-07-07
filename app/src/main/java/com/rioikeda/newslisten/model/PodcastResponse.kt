package com.rioikeda.newslisten.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ポッドキャストレスポンス DTO。
 *
 * 正本: backend/api/schemas.py:47-62（PodcastResponse）。フィールドは全てそこから直接転記する
 * （id / audio_url / status は委譲ブリーフの必須列挙には無かったが、schemas.py の実体を
 * 正本として優先し転記した）。
 *
 * WHY(ADR-059): segments は既存 Podcast ドキュメントには無いため default=null で後方互換。
 * クライアントは null を「トランスクリプト未提供」として graceful degradation する。
 */
@Serializable
data class PodcastResponse(
    val id: String,
    val type: String,
    @SerialName("article_ids") val articleIds: List<String>,
    val difficulty: String,
    @SerialName("audio_url") val audioUrl: String,
    @SerialName("japanese_intro_text") val japaneseIntroText: String,
    @SerialName("duration_seconds") val durationSeconds: Int,
    val status: String,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("playback_position_seconds") val playbackPositionSeconds: Double = 0.0,
    val title: String = "",
    val segments: List<TranscriptSegment>? = null,
    @SerialName("created_at") val createdAt: String,
)
