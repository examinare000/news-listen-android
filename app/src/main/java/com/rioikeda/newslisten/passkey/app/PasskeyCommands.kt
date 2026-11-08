package com.rioikeda.newslisten.passkey.app

import com.rioikeda.newslisten.account.app.AccountApi
import com.rioikeda.newslisten.auth.domain.IssuedSession
import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.passkey.PasskeyCancellationException
import com.rioikeda.newslisten.passkey.PasskeyProvider
import com.rioikeda.newslisten.passkey.PasskeyProviderException

/**
 * パスキーの登録・ログインの手順（options → Credential Manager → verify → hook）。
 *
 * hook は成功の後に 1 回だけ呼ぶ。`try` の中で呼ぶので、hook が投げた [ApiException] も他の段と同じ失敗に分類する。
 * hook に既定値は付けない（渡し忘れをコンパイルで検出する）。
 */
class PasskeyCommands(
    private val accountApi: AccountApi,
    private val passkeyProvider: PasskeyProvider,
    private val onRegistered: suspend () -> Unit,
    private val onLoginSuccess: suspend (IssuedSession) -> Unit,
) {
    suspend fun registerPasskey(): PasskeyRegisterOutcome =
        try {
            val options = accountApi.passkeyRegisterOptions()
            val responseJson = passkeyProvider.createCredential(options.options)
            accountApi.passkeyRegisterVerify(options.challengeId, responseJson)
            onRegistered()
            PasskeyRegisterOutcome.Registered
        } catch (e: PasskeyCancellationException) {
            PasskeyRegisterOutcome.Cancelled
        } catch (e: ApiException.Conflict) {
            PasskeyRegisterOutcome.Conflict
        } catch (e: ApiException) {
            PasskeyRegisterOutcome.Failed(e)
        } catch (e: PasskeyProviderException) {
            PasskeyRegisterOutcome.ProviderFailed(e)
        }

    suspend fun loginWithPasskey(): PasskeyLoginOutcome =
        try {
            val options = accountApi.passkeyLoginOptions()
            val responseJson = passkeyProvider.getCredential(options.options)
            val session = accountApi.passkeyLoginVerify(options.challengeId, responseJson)
            onLoginSuccess(session)
            PasskeyLoginOutcome.LoggedIn
        } catch (e: PasskeyCancellationException) {
            PasskeyLoginOutcome.Cancelled
        } catch (e: ApiException) {
            PasskeyLoginOutcome.Failed(e)
        } catch (e: PasskeyProviderException) {
            PasskeyLoginOutcome.ProviderFailed(e)
        }
}
