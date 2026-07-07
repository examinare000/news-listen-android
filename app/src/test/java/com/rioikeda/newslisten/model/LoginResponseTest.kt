package com.rioikeda.newslisten.model

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [LoginResponse] の toString マスキング検証。
 */
class LoginResponseTest {

    @Test
    fun toStringに平文のtokenが含まれない() {
        val user = UserResponse("u1", "member", "User One")
        val response = LoginResponse("session-token-abc123", user)
        val str = response.toString()

        assertFalse("平文トークンが含まれてはいけない", str.contains("session-token-abc123"))
        assert(str.contains("[REDACTED]")) { "tokenフィールドは[REDACTED]でマスクされるべき" }
    }
}
