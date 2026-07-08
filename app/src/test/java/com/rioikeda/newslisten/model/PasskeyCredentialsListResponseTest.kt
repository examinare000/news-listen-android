package com.rioikeda.newslisten.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PasskeyCredential / PasskeyCredentialsListResponse（GET /auth/passkey/credentials）の検証。
 *
 * 正本: backend/api/routers/passkey.py:62-76（PasskeyCredentialResponse/PasskeyCredentialsListResponse）。
 * public_key は除外済みレスポンスのため、DTO にも含めない。
 */
class PasskeyCredentialsListResponseTest {

    @Test
    fun 全フィールドを持つクレデンシャル一覧をデコードできる() {
        val json = """
            {"credentials":[
                {
                    "credential_id":"cred-1",
                    "username":"u",
                    "name":"iPhone",
                    "transports":["internal","hybrid"],
                    "aaguid":"aaguid-1",
                    "sign_count":3,
                    "created_at":"2026-01-01T00:00:00+00:00",
                    "last_used_at":"2026-01-02T00:00:00+00:00"
                }
            ]}
        """.trimIndent()

        val response = NewsListenJson.decodeFromString(PasskeyCredentialsListResponse.serializer(), json)

        val credential = response.credentials.single()
        assertEquals("cred-1", credential.credentialId)
        assertEquals("u", credential.username)
        assertEquals("iPhone", credential.name)
        assertEquals(listOf("internal", "hybrid"), credential.transports)
        assertEquals("aaguid-1", credential.aaguid)
        assertEquals(3, credential.signCount)
        assertEquals("2026-01-01T00:00:00+00:00", credential.createdAt)
        assertEquals("2026-01-02T00:00:00+00:00", credential.lastUsedAt)
    }

    @Test
    fun nameとaaguidとlast_used_atがnullのクレデンシャルをデコードできる() {
        val json = """
            {"credentials":[
                {
                    "credential_id":"cred-2",
                    "username":"u",
                    "name":null,
                    "transports":[],
                    "aaguid":null,
                    "sign_count":0,
                    "created_at":"2026-01-01T00:00:00+00:00",
                    "last_used_at":null
                }
            ]}
        """.trimIndent()

        val response = NewsListenJson.decodeFromString(PasskeyCredentialsListResponse.serializer(), json)

        val credential = response.credentials.single()
        assertNull(credential.name)
        assertNull(credential.aaguid)
        assertNull(credential.lastUsedAt)
        assertEquals(emptyList<String>(), credential.transports)
    }

    @Test
    fun 空一覧をデコードできる() {
        val json = """{"credentials":[]}"""

        val response = NewsListenJson.decodeFromString(PasskeyCredentialsListResponse.serializer(), json)

        assertEquals(emptyList<PasskeyCredential>(), response.credentials)
    }
}
