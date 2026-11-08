package com.rioikeda.newslisten.auth.domain

import com.rioikeda.newslisten.core.ApiException

/** login・パスキーの確立の結果。失敗は例外ではなく値で返し、文言への写しは presentation が持つ。 */
sealed interface LoginOutcome {
    data object Established : LoginOutcome

    data object InvalidCredentials : LoginOutcome

    data object SaveFailed : LoginOutcome

    data class Failed(val cause: ApiException) : LoginOutcome
}

/** サーバーが発行したセッション。ログへ平文のトークンを出さないよう、[toString] は伏せる。 */
data class IssuedSession(val token: String, val user: AccountUser) {
    override fun toString(): String = "IssuedSession(token=[REDACTED], user=$user)"
}
