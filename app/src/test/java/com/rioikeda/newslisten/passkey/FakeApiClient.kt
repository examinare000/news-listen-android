package com.rioikeda.newslisten.passkey

import com.rioikeda.newslisten.model.LoginResponse
import com.rioikeda.newslisten.model.PasskeyCredentialsListResponse
import com.rioikeda.newslisten.model.PasskeyOptionsResponse
import com.rioikeda.newslisten.network.BaseFakeApiClient
import kotlinx.serialization.json.JsonObject

/**
 * Passkey ViewModel 群（[PasskeyRegistrationViewModel]/[PasskeyLoginViewModel]/
 * [PasskeyCredentialsViewModel]）のテスト専用フェイク。
 *
 * このテストスイートで使う6メソッドのみ挙動を差し替え可能にする。それ以外は
 * スコープ外のため、誤って呼ばれた場合は即座に失敗させて検出できるよう例外を投げる
 * （auth/settings 等の既存 Fake と同じ設計方針）。
 */
class FakeApiClient(
    private val onPasskeyRegisterOptions: suspend () -> PasskeyOptionsResponse =
        { error("passkeyRegisterOptions is not stubbed") },
    private val onPasskeyRegisterVerify: suspend (challengeId: String, credential: JsonObject) -> Unit =
        { _, _ -> error("passkeyRegisterVerify is not stubbed") },
    private val onPasskeyLoginOptions: suspend (username: String?) -> PasskeyOptionsResponse =
        { error("passkeyLoginOptions is not stubbed") },
    private val onPasskeyLoginVerify: suspend (challengeId: String, credential: JsonObject) -> LoginResponse =
        { _, _ -> error("passkeyLoginVerify is not stubbed") },
    private val onListPasskeyCredentials: suspend () -> PasskeyCredentialsListResponse =
        { error("listPasskeyCredentials is not stubbed") },
    private val onDeletePasskeyCredential: suspend (credentialId: String) -> Unit =
        { error("deletePasskeyCredential is not stubbed") },
) : BaseFakeApiClient() {

    /** passkeyRegisterVerify に渡された最後の credential（送信内容の検証用）。 */
    var lastRegisterVerifyCredential: JsonObject? = null
        private set

    /** passkeyLoginVerify に渡された最後の credential（送信内容の検証用）。 */
    var lastLoginVerifyCredential: JsonObject? = null
        private set

    override suspend fun passkeyRegisterOptions(): PasskeyOptionsResponse = onPasskeyRegisterOptions()

    override suspend fun passkeyRegisterVerify(challengeId: String, credential: JsonObject) {
        lastRegisterVerifyCredential = credential
        onPasskeyRegisterVerify(challengeId, credential)
    }

    override suspend fun passkeyLoginOptions(username: String?): PasskeyOptionsResponse =
        onPasskeyLoginOptions(username)

    override suspend fun passkeyLoginVerify(challengeId: String, credential: JsonObject): LoginResponse {
        lastLoginVerifyCredential = credential
        return onPasskeyLoginVerify(challengeId, credential)
    }

    override suspend fun listPasskeyCredentials(): PasskeyCredentialsListResponse = onListPasskeyCredentials()

    override suspend fun deletePasskeyCredential(credentialId: String) = onDeletePasskeyCredential(credentialId)
}
