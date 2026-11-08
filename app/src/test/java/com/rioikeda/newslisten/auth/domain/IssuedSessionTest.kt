package com.rioikeda.newslisten.auth.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IssuedSessionTest {

    @Test
    fun `android-dec-84 IssuedSessionのtoStringはtokenを出さずREDACTEDにする`() {
        val user = AccountUser(username = "u", displayName = "U", role = Role.User, subjectId = SubjectId.of("u-1"))

        val text = IssuedSession(token = "secret-token", user = user).toString()

        assertFalse(text, text.contains("secret-token"))
        assertTrue(text, text.contains("token=[REDACTED]"))
    }
}
