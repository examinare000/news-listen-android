package com.rioikeda.newslisten.passkey.app

import com.rioikeda.newslisten.auth.domain.IssuedSession
import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.model.LoginResponse
import com.rioikeda.newslisten.model.PasskeyOptionsResponse
import com.rioikeda.newslisten.model.UserResponse
import com.rioikeda.newslisten.network.AccountApiAdapter
import com.rioikeda.newslisten.network.toIssuedSession
import com.rioikeda.newslisten.passkey.FakeApiClient
import com.rioikeda.newslisten.passkey.FakePasskeyProvider
import com.rioikeda.newslisten.passkey.PasskeyCancellationException
import com.rioikeda.newslisten.passkey.PasskeyProvider
import com.rioikeda.newslisten.passkey.PasskeyProviderException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PasskeyCommands` の手順（options → Credential Manager → verify → hook）と、
 * 失敗を `PasskeyLoginOutcome`/`PasskeyRegisterOutcome` に分類する規則の検証。
 */
class PasskeyCommandsTest {

    private val user = UserResponse(username = "u", role = "member", displayName = "U")
    private val options = PasskeyOptionsResponse(challengeId = "c-1", options = "opts")

    private fun commands(
        apiClient: FakeApiClient,
        passkeyProvider: PasskeyProvider = FakePasskeyProvider(),
        onRegistered: suspend () -> Unit = {},
        onLoginSuccess: suspend (IssuedSession) -> Unit = {},
    ) = PasskeyCommands(
        accountApi = AccountApiAdapter(apiClient),
        passkeyProvider = passkeyProvider,
        onRegistered = onRegistered,
        onLoginSuccess = onLoginSuccess,
    )

    // --- ログイン ---

    @Test
    fun `android-dec-87 loginWithPasskeyは成功でLoggedInを返しhookへIssuedSessionを渡す`() = runTest {
        val loginResponse = LoginResponse(token = "t-1", user = user)
        val received = mutableListOf<IssuedSession>()
        val commands = commands(
            apiClient = FakeApiClient(
                onPasskeyLoginOptions = { options },
                onPasskeyLoginVerify = { _, _ -> loginResponse },
            ),
            passkeyProvider = FakePasskeyProvider(onGetCredential = { """{"id":"cred-1"}""" }),
            onLoginSuccess = { received += it },
        )

        val outcome = commands.loginWithPasskey()

        assertTrue(outcome is PasskeyLoginOutcome.LoggedIn)
        assertEquals(1, received.size)
        assertEquals("t-1", received[0].token)
        assertEquals(loginResponse.toIssuedSession().user, received[0].user)
    }

    @Test
    fun `android-dec-87 loginWithPasskeyは取消でCancelledを返しhookを呼ばない`() = runTest {
        var verifyCalls = 0
        var hookCalls = 0
        val commands = commands(
            apiClient = FakeApiClient(
                onPasskeyLoginOptions = { options },
                onPasskeyLoginVerify = { _, _ -> verifyCalls++; LoginResponse("t", user) },
            ),
            passkeyProvider = FakePasskeyProvider(onGetCredential = { throw PasskeyCancellationException() }),
            onLoginSuccess = { hookCalls++ },
        )

        val outcome = commands.loginWithPasskey()

        assertTrue(outcome is PasskeyLoginOutcome.Cancelled)
        assertEquals(0, verifyCalls)
        assertEquals(0, hookCalls)
    }

    @Test
    fun `android-dec-87 loginWithPasskeyはverifyの失敗でFailedを返す`() = runTest {
        val failure = ApiException.Unauthorized()
        var hookCalls = 0
        val commands = commands(
            apiClient = FakeApiClient(
                onPasskeyLoginOptions = { options },
                onPasskeyLoginVerify = { _, _ -> throw failure },
            ),
            passkeyProvider = FakePasskeyProvider(onGetCredential = { """{"id":"cred-1"}""" }),
            onLoginSuccess = { hookCalls++ },
        )

        val outcome = commands.loginWithPasskey()

        assertSame(failure, (outcome as PasskeyLoginOutcome.Failed).cause)
        assertEquals(0, hookCalls)
    }

    @Test
    fun `android-dec-87 loginWithPasskeyはproviderの失敗でProviderFailedを返す`() = runTest {
        val failure = PasskeyProviderException("no provider")
        val commands = commands(
            apiClient = FakeApiClient(onPasskeyLoginOptions = { options }),
            passkeyProvider = FakePasskeyProvider(onGetCredential = { throw failure }),
        )

        val outcome = commands.loginWithPasskey()

        assertSame(failure, (outcome as PasskeyLoginOutcome.ProviderFailed).cause)
    }

    @Test
    fun `android-dec-87 loginWithPasskeyはhookが投げたApiExceptionもFailedで返す`() = runTest {
        val failure = ApiException.NetworkError(RuntimeException("offline"))
        var hookCalls = 0
        val commands = commands(
            apiClient = FakeApiClient(
                onPasskeyLoginOptions = { options },
                onPasskeyLoginVerify = { _, _ -> LoginResponse("t-1", user) },
            ),
            passkeyProvider = FakePasskeyProvider(onGetCredential = { """{"id":"cred-1"}""" }),
            onLoginSuccess = {
                hookCalls++
                throw failure
            },
        )

        val outcome = commands.loginWithPasskey()

        assertSame(failure, (outcome as PasskeyLoginOutcome.Failed).cause)
        assertEquals(1, hookCalls)
    }

    @Test
    fun `android-dec-87 loginWithPasskeyは不正なJSONの例外を捕まえない`() = runTest {
        var verifyCalls = 0
        val commands = commands(
            apiClient = FakeApiClient(
                onPasskeyLoginOptions = { options },
                onPasskeyLoginVerify = { _, _ -> verifyCalls++; LoginResponse("t", user) },
            ),
            passkeyProvider = FakePasskeyProvider(onGetCredential = { "not json" }),
        )

        var thrown: Throwable? = null
        try {
            commands.loginWithPasskey()
        } catch (e: Exception) {
            thrown = e
        }

        assertTrue(thrown is SerializationException || thrown is IllegalArgumentException)
        assertEquals(0, verifyCalls)
    }

    // --- 登録 ---

    @Test
    fun `android-dec-87 registerPasskeyは成功でRegisteredを返しhookを呼ぶ`() = runTest {
        var hookCalls = 0
        val commands = commands(
            apiClient = FakeApiClient(
                onPasskeyRegisterOptions = { options },
                onPasskeyRegisterVerify = { _, _ -> },
            ),
            passkeyProvider = FakePasskeyProvider(onCreateCredential = { """{"id":"cred-1"}""" }),
            onRegistered = { hookCalls++ },
        )

        val outcome = commands.registerPasskey()

        assertTrue(outcome is PasskeyRegisterOutcome.Registered)
        assertEquals(1, hookCalls)
    }

    @Test
    fun `android-dec-87 registerPasskeyは409でConflictを返しhookを呼ばない`() = runTest {
        var hookCalls = 0
        val commands = commands(
            apiClient = FakeApiClient(
                onPasskeyRegisterOptions = { options },
                onPasskeyRegisterVerify = { _, _ -> throw ApiException.Conflict() },
            ),
            passkeyProvider = FakePasskeyProvider(onCreateCredential = { """{"id":"cred-1"}""" }),
            onRegistered = { hookCalls++ },
        )

        val outcome = commands.registerPasskey()

        assertTrue(outcome is PasskeyRegisterOutcome.Conflict)
        assertEquals(0, hookCalls)
    }

    @Test
    fun `android-dec-87 registerPasskeyは取消とprovider失敗と通信失敗を区別する`() = runTest {
        val providerFailure = PasskeyProviderException("no provider")
        val networkFailure = ApiException.NetworkError(RuntimeException("offline"))
        var hookCalls = 0
        fun failing(provider: FakePasskeyProvider, verify: () -> Unit = {}) = commands(
            apiClient = FakeApiClient(
                onPasskeyRegisterOptions = { options },
                onPasskeyRegisterVerify = { _, _ -> verify() },
            ),
            passkeyProvider = provider,
            onRegistered = { hookCalls++ },
        )

        val cancelled = failing(FakePasskeyProvider(onCreateCredential = { throw PasskeyCancellationException() }))
            .registerPasskey()
        val providerFailed = failing(FakePasskeyProvider(onCreateCredential = { throw providerFailure }))
            .registerPasskey()
        val networkFailed = failing(
            FakePasskeyProvider(onCreateCredential = { """{"id":"cred-1"}""" }),
            verify = { throw networkFailure },
        ).registerPasskey()

        assertTrue(cancelled is PasskeyRegisterOutcome.Cancelled)
        assertSame(providerFailure, (providerFailed as PasskeyRegisterOutcome.ProviderFailed).cause)
        assertSame(networkFailure, (networkFailed as PasskeyRegisterOutcome.Failed).cause)
        assertEquals(0, hookCalls)
    }

    @Test
    fun `android-dec-87 registerPasskeyはhookが投げたApiExceptionをFailedで返す`() = runTest {
        val failure = ApiException.NetworkError(RuntimeException("offline"))
        var hookCalls = 0
        var verifyCalls = 0
        val commands = commands(
            apiClient = FakeApiClient(
                onPasskeyRegisterOptions = { options },
                onPasskeyRegisterVerify = { _, _ -> verifyCalls++ },
            ),
            passkeyProvider = FakePasskeyProvider(onCreateCredential = { """{"id":"cred-1"}""" }),
            onRegistered = {
                hookCalls++
                throw failure
            },
        )

        val outcome = commands.registerPasskey()

        assertSame(failure, (outcome as PasskeyRegisterOutcome.Failed).cause)
        assertEquals(1, hookCalls)
        assertEquals(1, verifyCalls)
    }

    @Test
    fun `android-dec-87 registerPasskeyはhookが投げたConflictをConflictで返す`() = runTest {
        var hookCalls = 0
        val commands = commands(
            apiClient = FakeApiClient(
                onPasskeyRegisterOptions = { options },
                onPasskeyRegisterVerify = { _, _ -> },
            ),
            passkeyProvider = FakePasskeyProvider(onCreateCredential = { """{"id":"cred-1"}""" }),
            onRegistered = {
                hookCalls++
                throw ApiException.Conflict()
            },
        )

        val outcome = commands.registerPasskey()

        assertTrue(outcome is PasskeyRegisterOutcome.Conflict)
        assertEquals(1, hookCalls)
    }

    // --- 移した test（旧: PasskeyLoginViewModelTest / PasskeyRegistrationViewModelTest。名前を保つ） ---

    @Test
    fun ログイン成功でoptionsからverifyまで完走しonLoginSuccessが呼ばれる() = runTest {
        var received: IssuedSession? = null
        val loginResponse = LoginResponse(token = "t-1", user = user).toIssuedSession()
        val apiClient = FakeApiClient(
            onPasskeyLoginOptions = { PasskeyOptionsResponse(challengeId = "c-1", options = "opts-json") },
            onPasskeyLoginVerify = { _, _ -> LoginResponse(token = "t-1", user = user) },
        )
        val passkeyProvider = FakePasskeyProvider(
            onGetCredential = { """{"id":"cred-1","type":"public-key"}""" },
        )
        val commands = commands(
            apiClient = apiClient,
            passkeyProvider = passkeyProvider,
            onLoginSuccess = { received = it },
        )

        commands.loginWithPasskey()

        assertEquals(loginResponse, received)
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
        val commands = commands(apiClient = apiClient, passkeyProvider = passkeyProvider)

        commands.loginWithPasskey()

        assertNull(receivedUsername)
    }

    @Test
    fun optionsのoptions文字列がそのままCredentialProviderへ渡る() = runTest {
        val apiClient = FakeApiClient(
            onPasskeyLoginOptions = { PasskeyOptionsResponse(challengeId = "c-1", options = "the-options-json") },
            onPasskeyLoginVerify = { _, _ -> LoginResponse(token = "t-1", user = user) },
        )
        val passkeyProvider = FakePasskeyProvider(onGetCredential = { """{"id":"cred-1"}""" })
        val commands = commands(apiClient = apiClient, passkeyProvider = passkeyProvider)

        commands.loginWithPasskey()

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
        val commands = commands(apiClient = apiClient, passkeyProvider = passkeyProvider)

        commands.loginWithPasskey()

        val sentCredential = requireNotNull(apiClient.lastLoginVerifyCredential)
        assertEquals("cred-1", sentCredential.getValue("id").jsonPrimitive.content)
        assertEquals("public-key", sentCredential.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun 登録成功でoptionsからverifyまで完走しonRegisteredが呼ばれる() = runTest {
        var registeredCalled = false
        val apiClient = FakeApiClient(
            onPasskeyRegisterOptions = { PasskeyOptionsResponse(challengeId = "c-1", options = "opts-json") },
            onPasskeyRegisterVerify = { _, _ -> },
        )
        val passkeyProvider = FakePasskeyProvider(
            onCreateCredential = { """{"id":"cred-1","type":"public-key"}""" },
        )
        val commands = commands(
            apiClient = apiClient,
            passkeyProvider = passkeyProvider,
            onRegistered = { registeredCalled = true },
        )

        commands.registerPasskey()

        assertTrue(registeredCalled)
    }

    @Test
    fun createCredentialのresponseJsonをJsonObjectにパースしてverifyへ送る() = runTest {
        val apiClient = FakeApiClient(
            onPasskeyRegisterOptions = { PasskeyOptionsResponse(challengeId = "c-1", options = "opts") },
            onPasskeyRegisterVerify = { _, _ -> },
        )
        val passkeyProvider = FakePasskeyProvider(
            onCreateCredential = { """{"id":"cred-1","type":"public-key"}""" },
        )
        val commands = commands(apiClient = apiClient, passkeyProvider = passkeyProvider)

        commands.registerPasskey()

        val sentCredential = requireNotNull(apiClient.lastRegisterVerifyCredential)
        assertEquals("cred-1", sentCredential.getValue("id").jsonPrimitive.content)
        assertEquals("public-key", sentCredential.getValue("type").jsonPrimitive.content)
    }
}
