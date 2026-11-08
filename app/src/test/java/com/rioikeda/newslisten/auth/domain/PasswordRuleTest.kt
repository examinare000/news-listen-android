package com.rioikeda.newslisten.auth.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** パスワード規則の案内文は、移す前の文と一字一句同じでなければならない。 */
class PasswordRuleTest {

    @Test
    fun `android-dec-90 GUIDANCEは基点の案内文と一字一句同じ`() {
        assertEquals(
            "パスワードは12文字以上で、英大文字・英小文字・数字・記号のうち3種類以上を組み合わせてください",
            PasswordRule.GUIDANCE,
        )
    }
}
