package com.rioikeda.newslisten.account

import com.rioikeda.newslisten.account.ui.PasswordGuidance
import org.junit.Assert.assertEquals
import org.junit.Test

/** パスワードの案内文は、移す前の文と一字一句同じでなければならない。 */
class PasswordGuidanceTest {

    @Test
    fun `android-rule-account-05 案内文は基点の案内文と一字一句同じ`() {
        assertEquals(
            "パスワードは12文字以上で、英大文字・英小文字・数字・記号のうち3種類以上を組み合わせてください",
            PasswordGuidance.TEXT,
        )
    }
}
