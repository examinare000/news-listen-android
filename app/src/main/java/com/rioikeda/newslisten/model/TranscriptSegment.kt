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
)
