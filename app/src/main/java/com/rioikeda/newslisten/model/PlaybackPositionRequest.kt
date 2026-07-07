package com.rioikeda.newslisten.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * PATCH /podcasts/{id}/position のリクエストボディ DTO。
 *
 * 正本: backend/api/schemas.py:102-103（UpdatePlaybackPositionRequest）。
 * position_seconds は ge=0（負値は 422）。範囲検証はサーバー側の責務のため本 DTO では行わない。
 */
@Serializable
data class PlaybackPositionRequest(
    @SerialName("position_seconds") val positionSeconds: Double,
)
