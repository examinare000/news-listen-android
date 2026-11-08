package com.rioikeda.newslisten.auth.domain

/** ログイン画面の入力の規則。 */
object LoginInput {
    /** ログインボタンを押せる条件。空でなければよく、空白は trim しない。 */
    fun canSubmit(username: String, password: String): Boolean =
        username.isNotEmpty() && password.isNotEmpty()
}
