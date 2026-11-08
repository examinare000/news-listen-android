package com.rioikeda.newslisten.account.app

import com.rioikeda.newslisten.auth.domain.AccountUser
import com.rioikeda.newslisten.auth.domain.IssuedSession

/**
 * アカウント・セッション・パスキーの往復の port。実装は失敗を例外のまま呼び手へ伝える。
 *
 * 戻り値は domain の型か、このファイルの部品だけ。DTO・JSON・リードモデルは出さない。
 */
interface AccountApi {
    suspend fun updateProfile(displayName: String): AccountUser

    suspend fun changePassword(currentPassword: String, newPassword: String)

    suspend fun listSessions(): List<SessionRecord>

    suspend fun revokeSession(id: String)

    /** 失効した件数を返す。 */
    suspend fun revokeOtherSessions(): Int

    suspend fun listPasskeyCredentials(): List<PasskeyRecord>

    suspend fun deletePasskeyCredential(credentialId: String)

    suspend fun passkeyRegisterOptions(): PasskeyOptions

    /** [credentialJson] は Credential Manager が返した JSON 文字列。解析は実装側が行う。 */
    suspend fun passkeyRegisterVerify(challengeId: String, credentialJson: String)

    /** 認証不要の discoverable credential フロー。username は渡さない。 */
    suspend fun passkeyLoginOptions(): PasskeyOptions

    suspend fun passkeyLoginVerify(challengeId: String, credentialJson: String): IssuedSession
}

data class SessionRecord(
    val id: String,
    val deviceLabel: String?,
    val createdAt: String,
    val lastUsedAt: String?,
    val current: Boolean,
)

data class PasskeyRecord(
    val credentialId: String,
    val name: String?,
    val createdAt: String,
)

data class PasskeyOptions(
    val challengeId: String,
    val options: String,
)
