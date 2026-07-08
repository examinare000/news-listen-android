package com.rioikeda.newslisten.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * OnboardingStatusResponse の検証。
 *
 * 正本: backend/api/schemas.py:222-223（OnboardingStatusResponse）。
 * GET /settings/onboarding と POST /settings/onboarding/complete が共通で返す形。
 */
class OnboardingStatusResponseTest {

    @Test
    fun onboarding_completedがtrueのJSONをデコードできる() {
        val json = """{"onboarding_completed": true}"""

        val response = NewsListenJson.decodeFromString(OnboardingStatusResponse.serializer(), json)

        assertEquals(true, response.onboardingCompleted)
    }

    @Test
    fun onboarding_completedがfalseのJSONをデコードできる() {
        val json = """{"onboarding_completed": false}"""

        val response = NewsListenJson.decodeFromString(OnboardingStatusResponse.serializer(), json)

        assertFalse(response.onboardingCompleted)
    }
}
