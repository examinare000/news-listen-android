package com.rioikeda.newslisten.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LoginRequest / UserResponse / LoginResponse の検証。
 *
 * 正本: backend/api/schemas.py:233-235（LoginRequest）, :238-243（UserResponse）,
 * :246-254（LoginResponse）。
 * LoginResponse は token フィールド（access_token ではない）。
 */
class AuthDecodingTest {

    @Test
    fun LoginRequestはusernameとpasswordをsnake_case不要でエンコードする() {
        val request = LoginRequest(username = "alice", password = "s3cret")

        val encoded = NewsListenJson.encodeToString(LoginRequest.serializer(), request)

        assertEquals("""{"username":"alice","password":"s3cret"}""", encoded)
    }

    @Test
    fun LoginResponseはtokenフィールドでデコードされaccess_tokenではない() {
        val json = """
            {
              "token": "jwt-token-value",
              "user": {
                "username": "alice",
                "role": "user",
                "display_name": "Alice"
              }
            }
        """.trimIndent()

        val response = NewsListenJson.decodeFromString(LoginResponse.serializer(), json)

        assertEquals("jwt-token-value", response.token)
        assertEquals("alice", response.user.username)
        assertEquals("user", response.user.role)
        assertEquals("Alice", response.user.displayName)
    }
}
