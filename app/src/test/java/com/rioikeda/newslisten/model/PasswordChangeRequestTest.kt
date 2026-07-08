package com.rioikeda.newslisten.model

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [PasswordChangeRequest] の toString マスキング検証（LoginRequestTest.kt と同じ方針）。
 */
class PasswordChangeRequestTest {

    @Test
    fun toStringに平文のcurrent_passwordが含まれない() {
        val request = PasswordChangeRequest(currentPassword = "old-secret", newPassword = "new-secret")
        val str = request.toString()

        assertFalse("平文の現パスワードが含まれてはいけない", str.contains("old-secret"))
        assert(str.contains("[REDACTED]")) { "current_password/new_password は [REDACTED] でマスクされるべき" }
    }

    @Test
    fun toStringに平文のnew_passwordが含まれない() {
        val request = PasswordChangeRequest(currentPassword = "old-secret", newPassword = "new-secret")
        val str = request.toString()

        assertFalse("平文の新パスワードが含まれてはいけない", str.contains("new-secret"))
    }
}
