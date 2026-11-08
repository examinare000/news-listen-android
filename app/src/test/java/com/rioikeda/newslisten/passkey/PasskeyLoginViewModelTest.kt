package com.rioikeda.newslisten.passkey

import com.rioikeda.newslisten.auth.domain.IssuedSession
import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.model.PasskeyOptionsResponse
import com.rioikeda.newslisten.network.AccountApiAdapter
import com.rioikeda.newslisten.passkey.app.PasskeyCommands
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PasskeyLoginViewModel] の挙動検証（フェーズ17 P17・issue #140）。
 *
 * ログイン画面からのパスキーログインフロー: login/options（discoverable credential、
 * username 不要）→ Credential Manager getCredential → responseJson を JsonObject に
 * 一段パース → login/verify → LoginResponse。
 */
class PasskeyLoginViewModelTest {

    private fun TestScope.newViewModel(
        apiClient: FakeApiClient = FakeApiClient(),
        passkeyProvider: PasskeyProvider = FakePasskeyProvider(),
        onLoginSuccess: suspend (IssuedSession) -> Unit = {},
    ): PasskeyLoginViewModel =
        PasskeyLoginViewModel(
            passkeyCommands = PasskeyCommands(
                accountApi = AccountApiAdapter(apiClient),
                passkeyProvider = passkeyProvider,
                onRegistered = {},
                onLoginSuccess = onLoginSuccess,
            ),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

    @Test
    fun ユーザーキャンセルはエラー表示にしない() = runTest {
        val apiClient = FakeApiClient(
            onPasskeyLoginOptions = { PasskeyOptionsResponse(challengeId = "c-1", options = "opts") },
        )
        val passkeyProvider = FakePasskeyProvider(
            onGetCredential = { throw PasskeyCancellationException() },
        )
        val viewModel = newViewModel(apiClient = apiClient, passkeyProvider = passkeyProvider)

        viewModel.loginWithPasskey()

        assertNull(viewModel.errorMessage.value)
        assertFalse(viewModel.isLoggingIn.value)
    }

    @Test
    fun verifyが401なら汎用エラー文言を表示する() = runTest {
        val apiClient = FakeApiClient(
            onPasskeyLoginOptions = { PasskeyOptionsResponse(challengeId = "c-1", options = "opts") },
            onPasskeyLoginVerify = { _, _ -> throw ApiException.HttpError(401) },
        )
        val passkeyProvider = FakePasskeyProvider(onGetCredential = { """{"id":"cred-1"}""" })
        val viewModel = newViewModel(apiClient = apiClient, passkeyProvider = passkeyProvider)

        viewModel.loginWithPasskey()

        assertEquals("パスキーでのログインに失敗しました", viewModel.errorMessage.value)
        assertFalse(viewModel.isLoggingIn.value)
    }

    @Test
    fun キャンセル以外のPasskeyProvider失敗は汎用エラー文言を表示する() = runTest {
        val apiClient = FakeApiClient(
            onPasskeyLoginOptions = { PasskeyOptionsResponse(challengeId = "c-1", options = "opts") },
        )
        val passkeyProvider = FakePasskeyProvider(
            onGetCredential = { throw PasskeyProviderException("no provider") },
        )
        val viewModel = newViewModel(apiClient = apiClient, passkeyProvider = passkeyProvider)

        viewModel.loginWithPasskey()

        assertEquals("パスキーでのログインに失敗しました", viewModel.errorMessage.value)
    }
}
