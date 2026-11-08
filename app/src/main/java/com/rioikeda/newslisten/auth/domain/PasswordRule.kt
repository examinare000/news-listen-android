package com.rioikeda.newslisten.auth.domain

/** パスワード規則の案内。規則の検査の正本は backend で、Android は案内の文言だけを持つ。 */
object PasswordRule {
    const val GUIDANCE = "パスワードは12文字以上で、英大文字・英小文字・数字・記号のうち3種類以上を組み合わせてください"
}
