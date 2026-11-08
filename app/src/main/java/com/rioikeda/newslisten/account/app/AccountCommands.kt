package com.rioikeda.newslisten.account.app

import com.rioikeda.newslisten.auth.app.AuthSession
import com.rioikeda.newslisten.core.ApiException

/**
 * アカウント・セッション・パスキー削除の書込。失敗は戻り値で返し、文言は持たない。
 *
 * `ApiException?` の戻り値は、成功が `null`、失敗が受け取った例外そのもの。
 */
class AccountCommands(
    private val accountApi: AccountApi,
    private val authSession: AuthSession,
) {
    suspend fun saveProfile(displayName: String): ApiException? =
        try {
            val updated = accountApi.updateProfile(displayName)
            authSession.applyProfileUpdate(updated.displayName)
            null
        } catch (e: ApiException) {
            e
        }

    suspend fun changePassword(currentPassword: String, newPassword: String): ApiException? =
        try {
            accountApi.changePassword(currentPassword, newPassword)
            null
        } catch (e: ApiException) {
            e
        }

    suspend fun revokeSession(id: String): ApiException? =
        try {
            accountApi.revokeSession(id)
            null
        } catch (e: ApiException) {
            e
        }

    suspend fun revokeOtherSessions(): RevokedSessionsReceipt =
        try {
            RevokedSessionsReceipt.Revoked(accountApi.revokeOtherSessions())
        } catch (e: ApiException) {
            RevokedSessionsReceipt.Failed(e)
        }

    suspend fun deletePasskey(credentialId: String): ApiException? =
        try {
            accountApi.deletePasskeyCredential(credentialId)
            null
        } catch (e: ApiException) {
            e
        }
}
