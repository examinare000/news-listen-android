package com.rioikeda.newslisten.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PasskeyOptionsResponse（register/options・login/options 共通レスポンス）の検証。
 *
 * 正本: backend/api/routers/passkey.py:48-51（PasskeyOptionsResponse）。
 * options は options_to_json() が返す JSON 文字列で、二重エンコードせずそのまま
 * Credential Manager の requestJson へ渡す（呼び出し側の責務）。
 */
class PasskeyOptionsResponseTest {

    @Test
    fun challenge_idとoptions文字列をデコードできる() {
        val json = """{"challenge_id":"c-1","options":"{\"challenge\":\"abc\"}"}"""

        val response = NewsListenJson.decodeFromString(PasskeyOptionsResponse.serializer(), json)

        assertEquals("c-1", response.challengeId)
        assertEquals("""{"challenge":"abc"}""", response.options)
    }
}
