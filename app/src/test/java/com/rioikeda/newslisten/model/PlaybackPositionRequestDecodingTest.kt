package com.rioikeda.newslisten.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PlaybackPositionRequest（PATCH /podcasts/{id}/position の body）の検証。
 *
 * 正本: backend/api/schemas.py:102-103（UpdatePlaybackPositionRequest）。
 * position_seconds は ge=0（負値は 422。バリデーションはサーバー側の責務であり、
 * 本 DTO はワイヤ形式の転写のみを担う）。
 */
class PlaybackPositionRequestDecodingTest {

    @Test
    fun positionSecondsがsnake_caseでエンコードされる() {
        val request = PlaybackPositionRequest(positionSeconds = 12.5)

        val encoded = NewsListenJson.encodeToString(PlaybackPositionRequest.serializer(), request)

        assertEquals("""{"position_seconds":12.5}""", encoded)
    }

    @Test
    fun position_secondsのJSONからデコードできる() {
        val json = """{"position_seconds": 30.0}"""

        val request = NewsListenJson.decodeFromString(PlaybackPositionRequest.serializer(), json)

        assertEquals(30.0, request.positionSeconds, 0.0001)
    }
}
