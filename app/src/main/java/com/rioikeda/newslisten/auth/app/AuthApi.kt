package com.rioikeda.newslisten.auth.app

import com.rioikeda.newslisten.auth.domain.AccountUser
import com.rioikeda.newslisten.auth.domain.IssuedSession

/** 認証の往復の port。実装は失敗を例外のまま呼び手へ伝える。 */
interface AuthApi {
    suspend fun me(): AccountUser

    suspend fun login(username: String, password: String): IssuedSession

    suspend fun logout(token: String)
}
