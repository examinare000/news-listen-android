package com.rioikeda.newslisten.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PasskeyLoginOptionsRequest（POST /auth/passkey/login/options の body）の検証。
 *
 * 正本: backend/api/routers/passkey.py:58-59（PasskeyLoginOptionsRequest）。username は
 * discoverable credential フロー前提で credential 列挙には使われない（backend コメント参照）が、
 * スキーマ互換のため任意で送れるようにする。未指定時は省略する。
 */
class PasskeyLoginOptionsRequestTest {

    @Test
    fun username未指定時は空オブジェクトになる() {
        val request = PasskeyLoginOptionsRequest()

        val encoded = NewsListenJson.encodeToString(PasskeyLoginOptionsRequest.serializer(), request)

        assertEquals("{}", encoded)
    }

    @Test
    fun username指定時はそのままエンコードする() {
        val request = PasskeyLoginOptionsRequest(username = "u")

        val encoded = NewsListenJson.encodeToString(PasskeyLoginOptionsRequest.serializer(), request)

        assertEquals("""{"username":"u"}""", encoded)
    }
}
