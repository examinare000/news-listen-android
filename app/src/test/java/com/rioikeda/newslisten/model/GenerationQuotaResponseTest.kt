package com.rioikeda.newslisten.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * GenerationQuotaResponse の検証。
 *
 * 正本: backend/api/schemas.py:137-147（GenerationQuotaResponse、issue #164・ADR-061）。
 * limit<=0（未設定含む）は無制限を意味し、remaining は null で返る。
 */
class GenerationQuotaResponseTest {

    @Test
    fun 通常時はlimit_used_remaining_reset_atをデコードできる() {
        val json = """
            {"limit": 20, "used": 3, "remaining": 17, "reset_at": "2026-07-09T00:00:00+00:00"}
        """.trimIndent()

        val quota = NewsListenJson.decodeFromString(GenerationQuotaResponse.serializer(), json)

        assertEquals(20, quota.limit)
        assertEquals(3, quota.used)
        assertEquals(17, quota.remaining)
        assertEquals("2026-07-09T00:00:00+00:00", quota.resetAt)
    }

    @Test
    fun 無制限時はremainingがnullでデコードできる() {
        val json = """
            {"limit": 0, "used": 5, "remaining": null, "reset_at": "2026-07-09T00:00:00+00:00"}
        """.trimIndent()

        val quota = NewsListenJson.decodeFromString(GenerationQuotaResponse.serializer(), json)

        assertEquals(0, quota.limit)
        assertNull(quota.remaining)
    }
}
