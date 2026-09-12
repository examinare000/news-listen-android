package com.rioikeda.newslisten.model

import kotlinx.serialization.Serializable

/**
 * 対話スクリプト内の1話者の発話（テキスト形式）DTO。
 *
 * 正本: backend/shared/models.py:164-172（TranscriptSegment）。
 * speaker は "A"|"B"（ADR-059: PodcastScript.dialogue の永続化用写像）。
 */
@Serializable
data class TranscriptSegment(
    val speaker: String,
    val text: String,
    // ADR-094 第一段階（issue #237）: 役割ラベル（"fact" | "commentary"）。
    // backend は常にキーを返す（未設定時 null）が、role 無しの旧レスポンスでも
    // このプロパティの既定値（null）によりキー欠落時もデコード可能。
    val role: String? = null,
)
