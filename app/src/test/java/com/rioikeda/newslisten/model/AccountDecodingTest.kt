package com.rioikeda.newslisten.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * アカウント管理系 DTO（フェーズ11 Task1）のエンコード/デコード検証。
 *
 * 正本: backend/api/schemas.py（ProfileUpdateRequest, PasswordChangeRequest,
 * SessionResponse, SessionsListResponse, RevokeSessionsResponse）。
 */
class AccountDecodingTest {

    @Test
    fun ProfileUpdateRequestはdisplay_nameでエンコードされる() {
        val request = ProfileUpdateRequest(displayName = "Alice")

        val encoded = NewsListenJson.encodeToString(ProfileUpdateRequest.serializer(), request)

        assertEquals("""{"display_name":"Alice"}""", encoded)
    }

    @Test
    fun PasswordChangeRequestはcurrent_passwordとnew_passwordでエンコードされる() {
        val request = PasswordChangeRequest(currentPassword = "old-pass", newPassword = "new-pass")

        val encoded = NewsListenJson.encodeToString(PasswordChangeRequest.serializer(), request)

        assertEquals("""{"current_password":"old-pass","new_password":"new-pass"}""", encoded)
    }

    @Test
    fun SessionResponseはdevice_labelとlast_used_atがnullでもデコードできる() {
        val json = """
            {
              "id": "s1",
              "device_label": null,
              "created_at": "2026-07-01T09:00:00+00:00",
              "last_used_at": null,
              "current": true
            }
        """.trimIndent()

        val response = NewsListenJson.decodeFromString(SessionResponse.serializer(), json)

        assertEquals("s1", response.id)
        assertNull(response.deviceLabel)
        assertEquals("2026-07-01T09:00:00+00:00", response.createdAt)
        assertNull(response.lastUsedAt)
        assertTrue(response.current)
    }

    @Test
    fun SessionResponseは全フィールドがある場合正しくデコードされる() {
        val json = """
            {
              "id": "s2",
              "device_label": "Pixel 8",
              "created_at": "2026-07-01T09:00:00+00:00",
              "last_used_at": "2026-07-02T10:00:00+00:00",
              "current": false
            }
        """.trimIndent()

        val response = NewsListenJson.decodeFromString(SessionResponse.serializer(), json)

        assertEquals("Pixel 8", response.deviceLabel)
        assertEquals("2026-07-02T10:00:00+00:00", response.lastUsedAt)
        assertFalse(response.current)
    }

    @Test
    fun SessionsListResponseはsessionsのリストをデコードする() {
        val json = """
            {
              "sessions": [
                {"id":"s1","device_label":null,"created_at":"2026-07-01T09:00:00+00:00","last_used_at":null,"current":true}
              ]
            }
        """.trimIndent()

        val response = NewsListenJson.decodeFromString(SessionsListResponse.serializer(), json)

        assertEquals(1, response.sessions.size)
        assertEquals("s1", response.sessions[0].id)
    }

    @Test
    fun RevokeSessionsResponseはrevoked_countをデコードする() {
        val json = """{"revoked_count": 3}"""

        val response = NewsListenJson.decodeFromString(RevokeSessionsResponse.serializer(), json)

        assertEquals(3, response.revokedCount)
    }
}
