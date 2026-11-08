package com.rioikeda.newslisten.auth.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ログインボタンを押せる条件（両方が空でない。trim しない）。 */
class LoginInputTest {

    @Test
    fun `android-dec-88 canSubmitは両方が空でないときだけ真`() {
        assertTrue(LoginInput.canSubmit("u", "p"))
    }

    @Test
    fun `android-dec-88 canSubmitはどちらかが空なら偽`() {
        assertFalse(LoginInput.canSubmit("", "p"))
        assertFalse(LoginInput.canSubmit("u", ""))
        assertFalse(LoginInput.canSubmit("", ""))
    }

    @Test
    fun `android-dec-88 canSubmitは空白だけの値をtrimせず真とする`() {
        assertTrue(LoginInput.canSubmit(" ", " "))
    }
}
