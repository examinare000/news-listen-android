package com.rioikeda.newslisten.model

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [LoginRequest] の toString マスキング検証。
 */
class LoginRequestTest {

    @Test
    fun toStringに平文のpasswordが含まれない() {
        val request = LoginRequest("user123", "secret-password")
        val str = request.toString()

        assertFalse("平文パスワードが含まれてはいけない", str.contains("secret-password"))
        assert(str.contains("[REDACTED]")) { "passwordフィールドは[REDACTED]でマスクされるべき" }
    }

    @Test
    fun toStringに平文のusernameは含まれる() {
        val request = LoginRequest("user123", "secret-password")
        val str = request.toString()

        assert(str.contains("user123")) { "usernameは平文で含まれるべき" }
    }
}
