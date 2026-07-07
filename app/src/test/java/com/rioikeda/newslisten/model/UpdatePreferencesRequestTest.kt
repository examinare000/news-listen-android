package com.rioikeda.newslisten.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UpdatePreferencesRequest（PUT /settings/preferences の body）の検証。
 *
 * 正本: backend/api/schemas.py:411-417（UpdatePreferencesRequest）。4フィールド全てが
 * optional な部分更新リクエスト。未指定フィールドは省略され、指定フィールドのみ変更する
 * （backend の model_copy(update=...) が None を除外する前提と対称）。
 */
class UpdatePreferencesRequestTest {

    @Test
    fun difficultyとspeedのみ指定時は他フィールドを省略してエンコードする() {
        val request = UpdatePreferencesRequest(
            defaultDifficulty = "toeic_600",
            defaultPlaybackSpeed = 1.25,
        )

        val encoded = NewsListenJson.encodeToString(UpdatePreferencesRequest.serializer(), request)

        assertEquals(
            """{"default_difficulty":"toeic_600","default_playback_speed":1.25}""",
            encoded,
        )
    }

    @Test
    fun 全フィールド未指定時は空オブジェクトになる() {
        val request = UpdatePreferencesRequest()

        val encoded = NewsListenJson.encodeToString(UpdatePreferencesRequest.serializer(), request)

        assertEquals("{}", encoded)
    }
}
