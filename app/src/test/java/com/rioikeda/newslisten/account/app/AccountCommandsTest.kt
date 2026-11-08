package com.rioikeda.newslisten.account.app

import com.rioikeda.newslisten.account.FakeApiClient
import com.rioikeda.newslisten.account.testAuthSession
import com.rioikeda.newslisten.auth.AuthState
import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.model.RevokeSessionsResponse
import com.rioikeda.newslisten.model.UserResponse
import com.rioikeda.newslisten.network.AccountApiAdapter
import com.rioikeda.newslisten.network.InMemorySessionStore
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import com.rioikeda.newslisten.passkey.FakeApiClient as PasskeyFakeApiClient

/** `AccountCommands` は書込 5 操作を結果の値で返し、`saveProfile` だけが `AuthSession` へ表示名を反映する。 */
class AccountCommandsTest {

    private val signedInUser = UserResponse(username = "u", role = "member", displayName = "元の表示名")

    private fun TestScope.commands(client: FakeApiClient = FakeApiClient()) =
        AccountCommands(AccountApiAdapter(client), testAuthSession())

    @Test
    fun `android-dec-89 changePasswordは成功でnullを返し失敗のApiExceptionをそのまま返す`() = runTest {
        val failure = ApiException.UnprocessableInput()

        val ok = commands(FakeApiClient(onChangePassword = { _, _ -> })).changePassword("old", "new")
        val ng = commands(FakeApiClient(onChangePassword = { _, _ -> throw failure })).changePassword("old", "new")

        assertNull(ok)
        assertSame(failure, ng)
    }

    @Test
    fun `android-dec-89 deletePasskeyは成功でnullを返し失敗のApiExceptionをそのまま返す`() = runTest {
        val deleted = mutableListOf<String>()
        val failure = ApiException.NotFound()
        val okClient = PasskeyFakeApiClient(onDeletePasskeyCredential = { deleted += it })
        val ngClient = PasskeyFakeApiClient(onDeletePasskeyCredential = { throw failure })

        val ok = AccountCommands(AccountApiAdapter(okClient), testAuthSession()).deletePasskey("cred-1")
        val ng = AccountCommands(AccountApiAdapter(ngClient), testAuthSession()).deletePasskey("cred-2")

        assertNull(ok)
        assertEquals(listOf("cred-1"), deleted)
        assertSame(failure, ng)
    }

    @Test
    fun `android-dec-89 revokeOtherSessionsは成功で件数を持つRevokedを返す`() = runTest {
        val commands = commands(FakeApiClient(onRevokeOtherSessions = { RevokeSessionsResponse(revokedCount = 2) }))

        val receipt = commands.revokeOtherSessions()

        assertEquals(2, (receipt as RevokedSessionsReceipt.Revoked).revokedCount)
    }

    @Test
    fun `android-dec-89 revokeOtherSessionsは失敗でFailedを返す`() = runTest {
        val failure = ApiException.HttpError(500)
        val commands = commands(FakeApiClient(onRevokeOtherSessions = { throw failure }))

        val receipt = commands.revokeOtherSessions()

        assertSame(failure, (receipt as RevokedSessionsReceipt.Failed).failure)
    }

    @Test
    fun `android-dec-89 revokeSessionは成功でnullを返し失敗のApiExceptionをそのまま返す`() = runTest {
        val failure = ApiException.HttpError(500)

        val ok = commands(FakeApiClient(onRevokeSession = { })).revokeSession("s1")
        val ng = commands(FakeApiClient(onRevokeSession = { throw failure })).revokeSession("s2")

        assertNull(ok)
        assertSame(failure, ng)
    }

    @Test
    fun revokeSessionはcurrentセッションでもガードなくAPIを呼ぶ() = runTest {
        // iOS SessionsViewModel.swift:48-63 準拠: 入口に current ガードは無く、
        // 失効ボタン非表示という UI 側の導線のみで防いでいる（AccountSettingsView.swift:194）。
        val apiClient = FakeApiClient(onRevokeSession = { })
        val commands = AccountCommands(AccountApiAdapter(apiClient), testAuthSession())

        commands.revokeSession(id = "s1")

        assertEquals(1, apiClient.revokeSessionCallCount)
    }

    @Test
    fun `android-dec-91 saveProfileはAuthSessionのapplyProfileUpdateを直接呼び表示名がauthStateに載る`() = runTest {
        val authSession = testAuthSession(
            apiClient = com.rioikeda.newslisten.auth.FakeApiClient(onMe = { signedInUser }),
            sessionStore = InMemorySessionStore(initialToken = "token-abc"),
        )
        authSession.refreshAuth()
        val commands = AccountCommands(
            AccountApiAdapter(FakeApiClient(onUpdateProfile = { signedInUser.copy(displayName = "新") })),
            authSession,
        )

        val failure = commands.saveProfile("新")

        assertNull(failure)
        assertEquals("新", (authSession.authState.value as AuthState.Authenticated).user.displayName)
    }

    @Test
    fun `android-dec-91 saveProfileは失敗でApiExceptionを返しauthStateを変えない`() = runTest {
        val authSession = testAuthSession(
            apiClient = com.rioikeda.newslisten.auth.FakeApiClient(onMe = { signedInUser }),
            sessionStore = InMemorySessionStore(initialToken = "token-abc"),
        )
        authSession.refreshAuth()
        val before = authSession.authState.value
        val failure = ApiException.HttpError(500)
        val commands = AccountCommands(
            AccountApiAdapter(FakeApiClient(onUpdateProfile = { throw failure })),
            authSession,
        )

        val returned = commands.saveProfile("新")

        assertSame(failure, returned)
        assertEquals(before, authSession.authState.value)
    }
}
