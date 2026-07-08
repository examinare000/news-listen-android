package com.rioikeda.newslisten.passkey

import com.rioikeda.newslisten.model.PasskeyCredential
import com.rioikeda.newslisten.model.PasskeyCredentialsListResponse
import com.rioikeda.newslisten.network.ApiException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PasskeyCredentialsViewModel] の挙動検証（フェーズ17 P17・issue #140）。
 *
 * 設定画面のパスキー一覧・削除。正本: backend/api/routers/passkey.py の
 * GET /auth/passkey/credentials・DELETE /auth/passkey/credentials/{id}。
 */
class PasskeyCredentialsViewModelTest {

    private val credential1 = PasskeyCredential(
        credentialId = "cred-1",
        username = "u",
        name = "iPhone",
        transports = listOf("internal"),
        aaguid = null,
        signCount = 0,
        createdAt = "2026-01-01T00:00:00+00:00",
        lastUsedAt = null,
    )
    private val credential2 = credential1.copy(credentialId = "cred-2", name = "Pixel")

    private fun TestScope.newViewModel(apiClient: FakeApiClient = FakeApiClient()): PasskeyCredentialsViewModel =
        PasskeyCredentialsViewModel(
            apiClient = apiClient,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

    @Test
    fun loadCredentialsは取得した一覧を公開する() = runTest {
        val apiClient = FakeApiClient(
            onListPasskeyCredentials = { PasskeyCredentialsListResponse(listOf(credential1, credential2)) },
        )
        val viewModel = newViewModel(apiClient)

        viewModel.loadCredentials()

        assertEquals(listOf(credential1, credential2), viewModel.credentials.value)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun loadCredentials失敗でerrorMessageに反映される() = runTest {
        val apiClient = FakeApiClient(
            onListPasskeyCredentials = { throw ApiException.NetworkError(RuntimeException("offline")) },
        )
        val viewModel = newViewModel(apiClient)

        viewModel.loadCredentials()

        assertEquals("パスキー一覧の取得に失敗しました", viewModel.errorMessage.value)
        assertEquals(emptyList<PasskeyCredential>(), viewModel.credentials.value)
    }

    @Test
    fun deleteCredentialは成功で一覧から即時除去する() = runTest {
        var deletedId: String? = null
        val apiClient = FakeApiClient(
            onListPasskeyCredentials = { PasskeyCredentialsListResponse(listOf(credential1, credential2)) },
            onDeletePasskeyCredential = { id -> deletedId = id },
        )
        val viewModel = newViewModel(apiClient)
        viewModel.loadCredentials()

        viewModel.deleteCredential("cred-1")

        assertEquals("cred-1", deletedId)
        assertEquals(listOf(credential2), viewModel.credentials.value)
    }

    @Test
    fun deleteCredential失敗でerrorMessageに反映され一覧は変わらない() = runTest {
        val apiClient = FakeApiClient(
            onListPasskeyCredentials = { PasskeyCredentialsListResponse(listOf(credential1)) },
            onDeletePasskeyCredential = { throw ApiException.NetworkError(RuntimeException("offline")) },
        )
        val viewModel = newViewModel(apiClient)
        viewModel.loadCredentials()

        viewModel.deleteCredential("cred-1")

        assertEquals("パスキーの削除に失敗しました", viewModel.errorMessage.value)
        assertEquals(listOf(credential1), viewModel.credentials.value)
    }

    @Test
    fun loadCredentials中はisLoadingがtrueになりその後falseに戻る() = runTest {
        var isLoadingDuringCall = false
        var viewModel: PasskeyCredentialsViewModel? = null
        val apiClient = FakeApiClient(
            onListPasskeyCredentials = {
                // ロード中（API 呼び出し実行中）に isLoading が true であることを検証する
                isLoadingDuringCall = viewModel?.isLoading?.value ?: false
                PasskeyCredentialsListResponse(emptyList())
            },
        )
        viewModel = newViewModel(apiClient)

        viewModel.loadCredentials()

        assertEquals(true, isLoadingDuringCall)
        assertEquals(false, viewModel.isLoading.value)
    }
}
