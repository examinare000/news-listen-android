package com.rioikeda.newslisten.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GET /users/me/generation-quota のレスポンス（issue #164・ADR-061）。
 *
 * 正本: backend/api/schemas.py:137-147（GenerationQuotaResponse）。
 * limit<=0（未設定含む）は無制限を意味し、remaining は null で返る。remaining は
 * デフォルト値を持たない必須フィールド（値としての null は許容、キー自体は必須）。
 */
@Serializable
data class GenerationQuotaResponse(
    val limit: Int,
    val used: Int,
    val remaining: Int?,
    @SerialName("reset_at") val resetAt: String,
)
