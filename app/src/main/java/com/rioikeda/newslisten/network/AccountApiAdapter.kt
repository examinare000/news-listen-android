package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.account.app.AccountApi
import com.rioikeda.newslisten.account.app.PasskeyOptions
import com.rioikeda.newslisten.account.app.PasskeyRecord
import com.rioikeda.newslisten.account.app.SessionRecord
import com.rioikeda.newslisten.auth.domain.AccountUser
import com.rioikeda.newslisten.auth.domain.IssuedSession
import com.rioikeda.newslisten.model.NewsListenJson
import com.rioikeda.newslisten.model.PasskeyCredential
import com.rioikeda.newslisten.model.SessionResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** [ApiClient] のアカウント系 11 操作を包み、DTO を port の部品・domain の型に変える。例外は呼び手へそのまま伝える。 */
internal class AccountApiAdapter(private val apiClient: ApiClient) : AccountApi {
    override suspend fun updateProfile(displayName: String): AccountUser =
        apiClient.updateProfile(displayName).toAccountUser()

    override suspend fun changePassword(currentPassword: String, newPassword: String) =
        apiClient.changePassword(currentPassword, newPassword)

    override suspend fun listSessions(): List<SessionRecord> =
        apiClient.listSessions().sessions.map { it.toSessionRecord() }

    override suspend fun revokeSession(id: String) = apiClient.revokeSession(id)

    override suspend fun revokeOtherSessions(): Int = apiClient.revokeOtherSessions().revokedCount

    override suspend fun listPasskeyCredentials(): List<PasskeyRecord> =
        apiClient.listPasskeyCredentials().credentials.map { it.toPasskeyRecord() }

    override suspend fun deletePasskeyCredential(credentialId: String) =
        apiClient.deletePasskeyCredential(credentialId)

    override suspend fun passkeyRegisterOptions(): PasskeyOptions {
        val response = apiClient.passkeyRegisterOptions()
        return PasskeyOptions(challengeId = response.challengeId, options = response.options)
    }

    override suspend fun passkeyRegisterVerify(challengeId: String, credentialJson: String) =
        apiClient.passkeyRegisterVerify(challengeId, parseCredential(credentialJson))

    // username は常に null（discoverable credential フロー。ユーザー列挙 oracle 防止のため backend は
    // username を credential の列挙に使わない）。
    override suspend fun passkeyLoginOptions(): PasskeyOptions {
        val response = apiClient.passkeyLoginOptions(null)
        return PasskeyOptions(challengeId = response.challengeId, options = response.options)
    }

    override suspend fun passkeyLoginVerify(challengeId: String, credentialJson: String): IssuedSession =
        apiClient.passkeyLoginVerify(challengeId, parseCredential(credentialJson)).toIssuedSession()

    private fun parseCredential(credentialJson: String): JsonObject =
        NewsListenJson.parseToJsonElement(credentialJson).jsonObject
}

private fun SessionResponse.toSessionRecord(): SessionRecord =
    SessionRecord(id = id, deviceLabel = deviceLabel, createdAt = createdAt, lastUsedAt = lastUsedAt, current = current)

private fun PasskeyCredential.toPasskeyRecord(): PasskeyRecord =
    PasskeyRecord(credentialId = credentialId, name = name, createdAt = createdAt)
