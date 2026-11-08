package com.rioikeda.newslisten.passkey

import com.rioikeda.newslisten.account.app.AccountCommands
import com.rioikeda.newslisten.account.app.SessionQueries
import com.rioikeda.newslisten.account.testAuthSession
import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.model.PasskeyCredential
import com.rioikeda.newslisten.model.PasskeyCredentialsListResponse
import com.rioikeda.newslisten.network.AccountApiAdapter
import com.rioikeda.newslisten.passkey.app.PasskeyRow
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

    /** 画面に出る値（read model）。fake には [response] で DTO に直して渡す。 */
    private val credential1 = PasskeyRow(
        credentialId = "cred-1",
        name = "iPhone",
        createdAt = "2026-01-01T00:00:00+00:00",
    )
    private val credential2 = credential1.copy(credentialId = "cred-2", name = "Pixel")

    private fun response(rows: List<PasskeyRow>) = PasskeyCredentialsListResponse(
        rows.map {
            PasskeyCredential(
                credentialId = it.credentialId,
                username = "u",
                name = it.name,
                transports = listOf("internal"),
                aaguid = null,
                signCount = 0,
                createdAt = it.createdAt,
                lastUsedAt = null,
            )
        },
    )

    private fun TestScope.newViewModel(apiClient: FakeApiClient = FakeApiClient()): PasskeyCredentialsViewModel {
        val accountApi = AccountApiAdapter(apiClient)
        return PasskeyCredentialsViewModel(
            accountCommands = AccountCommands(accountApi, testAuthSession()),
            sessionQueries = SessionQueries(accountApi),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
    }

    @Test
    fun loadCredentialsは取得した一覧を公開する() = runTest {
        val apiClient = FakeApiClient(
            onListPasskeyCredentials = { response(listOf(credential1, credential2)) },
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
        assertEquals(emptyList<PasskeyRow>(), viewModel.credentials.value)
    }

    @Test
    fun deleteCredentialは成功で一覧から即時除去する() = runTest {
        var deletedId: String? = null
        val apiClient = FakeApiClient(
            onListPasskeyCredentials = { response(listOf(credential1, credential2)) },
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
            onListPasskeyCredentials = { response(listOf(credential1)) },
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
                response(emptyList())
            },
        )
        viewModel = newViewModel(apiClient)

        viewModel.loadCredentials()

        assertEquals(true, isLoadingDuringCall)
        assertEquals(false, viewModel.isLoading.value)
    }
}
