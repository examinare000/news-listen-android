package com.rioikeda.newslisten.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PreferencesResponse の検証。
 *
 * 正本: backend/api/schemas.py:402-408（PreferencesResponse）。4フィールド全てが必須。
 */
class PreferencesResponseDecodingTest {

    @Test
    fun フィールド4つ全てをデコードできる() {
        val json = """
            {
              "default_difficulty": "toeic_600",
              "default_playback_speed": 1.25,
              "digest_enabled": true,
              "digest_article_count": 5
            }
        """.trimIndent()

        val prefs = NewsListenJson.decodeFromString(PreferencesResponse.serializer(), json)

        assertEquals("toeic_600", prefs.defaultDifficulty)
        assertEquals(1.25, prefs.defaultPlaybackSpeed, 0.0001)
        assertEquals(true, prefs.digestEnabled)
        assertEquals(5, prefs.digestArticleCount)
    }
}
