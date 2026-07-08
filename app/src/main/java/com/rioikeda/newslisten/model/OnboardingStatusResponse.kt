package com.rioikeda.newslisten.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GET /settings/onboarding と POST /settings/onboarding/complete 共通のレスポンス DTO。
 *
 * 正本: backend/api/schemas.py:222-223（OnboardingStatusResponse）。
 */
@Serializable
data class OnboardingStatusResponse(
    @SerialName("onboarding_completed") val onboardingCompleted: Boolean,
)
