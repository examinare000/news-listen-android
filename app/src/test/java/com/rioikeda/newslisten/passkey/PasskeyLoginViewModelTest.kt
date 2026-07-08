package com.rioikeda.newslisten.passkey

import com.rioikeda.newslisten.model.LoginResponse
import com.rioikeda.newslisten.model.PasskeyOptionsResponse
import com.rioikeda.newslisten.model.UserResponse
import com.rioikeda.newslisten.network.ApiException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
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

    private val user = UserResponse(username = "u", role = "member", displayName = "U")

    private fun TestScope.newViewModel(
        apiClient: FakeApiClient = FakeApiClient(),
        passkeyProvider: PasskeyProvider = FakePasskeyProvider(),
        onLoginSuccess: suspend (LoginResponse) -> Unit = {},
    ): PasskeyLoginViewModel =
        PasskeyLoginViewModel(
            apiClient = apiClient,
            passkeyProvider = passkeyProvider,
            dispatcher = StandardTestDispatcher(testScheduler),
            onLoginSuccess = onLoginSuccess,
        )

    @Test
    fun ログイン成功でoptionsからverifyまで完走しonLoginSuccessが呼ばれる() = runTest {
        var received: LoginResponse? = null
        val loginResponse = LoginResponse(token = "t-1", user = user)
        val apiClient = FakeApiClient(
            onPasskeyLoginOptions = { PasskeyOptionsResponse(challengeId = "c-1", options = "opts-json") },
            onPasskeyLoginVerify = { _, _ -> loginResponse },
        )
        val passkeyProvider = FakePasskeyProvider(
            onGetCredential = { """{"id":"cred-1","type":"public-key"}""" },
        )
        val viewModel = newViewModel(
            apiClient = apiClient,
            passkeyProvider = passkeyProvider,
            onLoginSuccess = { received = it },
        )

        viewModel.loginWithPasskey()

        assertEquals(loginResponse, received)
        assertNull(viewModel.errorMessage.value)
        assertFalse(viewModel.isLoggingIn.value)
    }

    @Test
    fun usernameを渡さずdiscoverableフローでoptionsを取得する() = runTest {
        var receivedUsername: String? = "unset"
        val apiClient = FakeApiClient(
            onPasskeyLoginOptions = { username ->
                receivedUsername = username
                PasskeyOptionsResponse(challengeId = "c-1", options = "opts")
            },
            onPasskeyLoginVerify = { _, _ -> LoginResponse(token = "t-1", user = user) },
        )
        val passkeyProvider = FakePasskeyProvider(onGetCredential = { """{"id":"cred-1"}""" })
        val viewModel = newViewModel(apiClient = apiClient, passkeyProvider = passkeyProvider)

        viewModel.loginWithPasskey()

        assertNull(receivedUsername)
    }

    @Test
    fun optionsのoptions文字列がそのままCredentialProviderへ渡る() = runTest {
        val apiClient = FakeApiClient(
            onPasskeyLoginOptions = { PasskeyOptionsResponse(challengeId = "c-1", options = "the-options-json") },
            onPasskeyLoginVerify = { _, _ -> LoginResponse(token = "t-1", user = user) },
        )
        val passkeyProvider = FakePasskeyProvider(onGetCredential = { """{"id":"cred-1"}""" })
        val viewModel = newViewModel(apiClient = apiClient, passkeyProvider = passkeyProvider)

        viewModel.loginWithPasskey()

        assertEquals("the-options-json", passkeyProvider.lastGetCredentialOptionsJson)
    }

    @Test
    fun getCredentialのresponseJsonをJsonObjectにパースしてverifyへ送る() = runTest {
        val apiClient = FakeApiClient(
            onPasskeyLoginOptions = { PasskeyOptionsResponse(challengeId = "c-1", options = "opts") },
            onPasskeyLoginVerify = { _, _ -> LoginResponse(token = "t-1", user = user) },
        )
        val passkeyProvider = FakePasskeyProvider(
            onGetCredential = { """{"id":"cred-1","type":"public-key"}""" },
        )
        val viewModel = newViewModel(apiClient = apiClient, passkeyProvider = passkeyProvider)

        viewModel.loginWithPasskey()

        val sentCredential = requireNotNull(apiClient.lastLoginVerifyCredential)
        assertEquals("cred-1", sentCredential.getValue("id").jsonPrimitive.content)
        assertEquals("public-key", sentCredential.getValue("type").jsonPrimitive.content)
    }

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
