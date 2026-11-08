package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.auth.app.AuthApi
import com.rioikeda.newslisten.auth.domain.AccountUser
import com.rioikeda.newslisten.auth.domain.IssuedSession
import com.rioikeda.newslisten.auth.domain.Role
import com.rioikeda.newslisten.auth.domain.SubjectId
import com.rioikeda.newslisten.model.LoginResponse
import com.rioikeda.newslisten.model.UserResponse

/** [ApiClient] の認証 3 操作を包み、DTO を domain の型に変える。例外は呼び手へそのまま伝える。 */
internal class AuthApiAdapter(private val apiClient: ApiClient) : AuthApi {
    override suspend fun me(): AccountUser = apiClient.me().toAccountUser()

    override suspend fun login(username: String, password: String): IssuedSession =
        apiClient.login(username, password).toIssuedSession()

    override suspend fun logout(token: String) = apiClient.logout(token)
}

internal fun UserResponse.toAccountUser(): AccountUser =
    AccountUser(
        username = username,
        displayName = displayName,
        role = Role.of(role),
        subjectId = SubjectId.of(userId),
    )

internal fun LoginResponse.toIssuedSession(): IssuedSession =
    IssuedSession(token = token, user = user.toAccountUser())
