package com.rioikeda.newslisten.auth

import com.rioikeda.newslisten.model.UserResponse
import com.rioikeda.newslisten.network.toAccountUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubjectTest {
    private fun user(userId: String?, username: String = "alice") =
        UserResponse(username = username, role = "user", displayName = "Alice", userId = userId).toAccountUser()

    @Test
    fun `android-rule-account-03 Subjectのuser_idは英数字とハイフンとアンダースコアだけが通り空と記号と欠落はnullになる`() {
        val cases: List<Pair<String?, String?>> = listOf(
            "abc123_-XYZ" to "abc123_-XYZ",
            "u1" to "u1",
            "" to null,
            "a b" to null,
            "a/b" to null,
            "a.b" to null,
            "../evil" to null,
            "u1\n" to null,
            "日本語" to null,
            null to null,
        )

        cases.forEach { (input, expected) ->
            assertEquals("userId=$input", expected, Subject.from(user(input)).userId)
        }
    }

    @Test
    fun `android-rule-account-03 Subjectはusernameを保持し欠落のuser_idでも生成できる`() {
        val subject = Subject.from(user(userId = null, username = "bob"))

        assertEquals("bob", subject.username)
        assertNull(subject.userId)
        assertEquals(Subject(userId = "u1", username = "alice"), Subject.from(user("u1")))
    }
}
