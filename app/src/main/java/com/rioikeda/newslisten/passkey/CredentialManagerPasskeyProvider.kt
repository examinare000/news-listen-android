package com.rioikeda.newslisten.passkey

import android.content.Context
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException

/**
 * androidx.credentials.CredentialManager による [PasskeyProvider] 実体（ADR-066）。
 *
 * WHY Activity Context が必須: CredentialManager.createCredential/getCredential は
 * システム UI（Google パスワードマネージャー等の bottom sheet）を表示するため、Activity
 * Context を要求する（ApplicationContext では実行時例外になる）。呼び出し元
 * （MainActivity）が自身（Activity）を渡して構築する。
 *
 * Android フレームワーク（実機/system UI）依存のため単体テスト対象外
 * （[KeystoreSessionStore] と同じ方針。ロジックは薄い委譲 + 例外変換のみに留める）。
 */
class CredentialManagerPasskeyProvider(
    private val activityContext: Context,
) : PasskeyProvider {
    private val credentialManager = CredentialManager.create(activityContext)

    override suspend fun createCredential(optionsJson: String): String {
        val request = CreatePublicKeyCredentialRequest(requestJson = optionsJson)
        try {
            val response = credentialManager.createCredential(activityContext, request)
            val typed = response as? CreatePublicKeyCredentialResponse
                ?: throw PasskeyProviderException("unexpected credential response type", null)
            return typed.registrationResponseJson
        } catch (e: CreateCredentialCancellationException) {
            throw PasskeyCancellationException(e)
        } catch (e: CreateCredentialException) {
            throw PasskeyProviderException(e.message ?: "passkey registration failed", e)
        }
    }

    override suspend fun getCredential(optionsJson: String): String {
        val option = GetPublicKeyCredentialOption(requestJson = optionsJson)
        val request = GetCredentialRequest(credentialOptions = listOf(option))
        try {
            val response = credentialManager.getCredential(activityContext, request)
            val credential = response.credential as? PublicKeyCredential
                ?: throw PasskeyProviderException("unexpected credential type", null)
            return credential.authenticationResponseJson
        } catch (e: GetCredentialCancellationException) {
            throw PasskeyCancellationException(e)
        } catch (e: GetCredentialException) {
            throw PasskeyProviderException(e.message ?: "passkey authentication failed", e)
        }
    }
}
