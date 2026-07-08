package com.rioikeda.newslisten.model

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PasskeyVerifyRequest（register/verify・login/verify 共通のリクエストボディ）の検証。
 *
 * 正本: backend/api/routers/passkey.py:53-55（PasskeyVerifyRequest）。credential は
 * Credential Manager が返す responseJson（文字列）を1段パースした JsonObject をそのまま送る
 * （backend が `credential: dict` を要求するため。生文字列のまま送ると 422 になる）。
 */
class PasskeyVerifyRequestTest {

    @Test
    fun challenge_idとcredentialオブジェクトをsnake_caseでエンコードする() {
        val credential = buildJsonObject {
            put("id", "cred-1")
            put("type", "public-key")
        }
        val request = PasskeyVerifyRequest(challengeId = "c-1", credential = credential)

        val encoded = NewsListenJson.encodeToString(PasskeyVerifyRequest.serializer(), request)

        assertEquals(
            """{"challenge_id":"c-1","credential":{"id":"cred-1","type":"public-key"}}""",
            encoded,
        )
    }
}
