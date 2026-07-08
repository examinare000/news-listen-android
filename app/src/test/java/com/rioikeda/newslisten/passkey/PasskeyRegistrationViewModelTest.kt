package com.rioikeda.newslisten.passkey

import com.rioikeda.newslisten.model.PasskeyOptionsResponse
import com.rioikeda.newslisten.network.ApiException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PasskeyRegistrationViewModel] の挙動検証（フェーズ17 P17・issue #140）。
 *
 * 設定画面からの「パスキーを追加」フロー: register/options → Credential Manager
 * createCredential → responseJson を JsonObject に一段パース → register/verify。
 */
class PasskeyRegistrationViewModelTest {

    private fun TestScope.newViewModel(
        apiClient: FakeApiClient = FakeApiClient(),
        passkeyProvider: PasskeyProvider = FakePasskeyProvider(),
        onRegistered: suspend () -> Unit = {},
    ): PasskeyRegistrationViewModel =
        PasskeyRegistrationViewModel(
            apiClient = apiClient,
            passkeyProvider = passkeyProvider,
            dispatcher = StandardTestDispatcher(testScheduler),
            onRegistered = onRegistered,
        )

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
        val viewModel = newViewModel(
            apiClient = apiClient,
            passkeyProvider = passkeyProvider,
            onRegistered = { registeredCalled = true },
        )

        viewModel.register()

        assertTrue(registeredCalled)
        assertNull(viewModel.errorMessage.value)
        assertFalse(viewModel.isRegistering.value)
    }

    @Test
    fun optionsのoptions文字列がそのままCredentialProviderへ渡る() = runTest {
        val apiClient = FakeApiClient(
            onPasskeyRegisterOptions = { PasskeyOptionsResponse(challengeId = "c-1", options = "the-options-json") },
            onPasskeyRegisterVerify = { _, _ -> },
        )
        val passkeyProvider = FakePasskeyProvider(
            onCreateCredential = { """{"id":"cred-1"}""" },
        )
        val viewModel = newViewModel(apiClient = apiClient, passkeyProvider = passkeyProvider)

        viewModel.register()

        assertEquals("the-options-json", passkeyProvider.lastCreateCredentialOptionsJson)
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
        val viewModel = newViewModel(apiClient = apiClient, passkeyProvider = passkeyProvider)

        viewModel.register()

        val sentCredential = requireNotNull(apiClient.lastRegisterVerifyCredential)
        assertEquals("cred-1", sentCredential.getValue("id").jsonPrimitive.content)
        assertEquals("public-key", sentCredential.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun ユーザーキャンセルはエラー表示にしない() = runTest {
        val passkeyProvider = FakePasskeyProvider(
            onCreateCredential = { throw PasskeyCancellationException() },
        )
        val apiClient = FakeApiClient(
            onPasskeyRegisterOptions = { PasskeyOptionsResponse(challengeId = "c-1", options = "opts") },
        )
        val viewModel = newViewModel(apiClient = apiClient, passkeyProvider = passkeyProvider)

        viewModel.register()

        assertNull(viewModel.errorMessage.value)
        assertFalse(viewModel.isRegistering.value)
    }

    @Test
    fun verifyが409なら重複登録のエラー文言を表示する() = runTest {
        val apiClient = FakeApiClient(
            onPasskeyRegisterOptions = { PasskeyOptionsResponse(challengeId = "c-1", options = "opts") },
            onPasskeyRegisterVerify = { _, _ -> throw ApiException.HttpError(409) },
        )
        val passkeyProvider = FakePasskeyProvider(onCreateCredential = { """{"id":"cred-1"}""" })
        val viewModel = newViewModel(apiClient = apiClient, passkeyProvider = passkeyProvider)

        viewModel.register()

        assertEquals("このパスキーは既に登録されています", viewModel.errorMessage.value)
    }

    @Test
    fun verifyが通信エラーなら汎用エラー文言を表示する() = runTest {
        val apiClient = FakeApiClient(
            onPasskeyRegisterOptions = { PasskeyOptionsResponse(challengeId = "c-1", options = "opts") },
            onPasskeyRegisterVerify = { _, _ -> throw ApiException.NetworkError(RuntimeException("offline")) },
        )
        val passkeyProvider = FakePasskeyProvider(onCreateCredential = { """{"id":"cred-1"}""" })
        val viewModel = newViewModel(apiClient = apiClient, passkeyProvider = passkeyProvider)

        viewModel.register()

        assertEquals("パスキーの登録に失敗しました", viewModel.errorMessage.value)
    }

    @Test
    fun キャンセル以外のPasskeyProvider失敗は汎用エラー文言を表示する() = runTest {
        val apiClient = FakeApiClient(
            onPasskeyRegisterOptions = { PasskeyOptionsResponse(challengeId = "c-1", options = "opts") },
        )
        val passkeyProvider = FakePasskeyProvider(
            onCreateCredential = { throw PasskeyProviderException("no provider") },
        )
        val viewModel = newViewModel(apiClient = apiClient, passkeyProvider = passkeyProvider)

        viewModel.register()

        assertEquals("パスキーの登録に失敗しました", viewModel.errorMessage.value)
        assertFalse(viewModel.isRegistering.value)
    }
}
