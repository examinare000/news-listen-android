package com.rioikeda.newslisten.auth

import com.rioikeda.newslisten.auth.app.AuthSession
import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.model.LoginResponse
import com.rioikeda.newslisten.model.PreferencesResponse
import com.rioikeda.newslisten.model.UserResponse
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.AuthApiAdapter
import com.rioikeda.newslisten.network.InMemorySessionStore
import com.rioikeda.newslisten.network.SessionStore
import com.rioikeda.newslisten.network.toAccountUser
import com.rioikeda.newslisten.network.toIssuedSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AuthViewModel] の挙動検証（`loginErrorMessage` の文言と [AuthSession] への中継）。session の遷移・失効・離脱の検証は主に AuthSessionTest が持ち、
 * ここには中継 6 操作だけで書ける logout・refresh の経路の test を一部残す。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/AppState.swift:130-178（refreshAuth/refreshPreferences/logout）、
 * ios/NewsListenApp/NewsListenApp/Auth/LoginViewModel.swift（ログインのエラー文言）のミラー。
 *
 * android-slice-auth-01（認証失効の型分離と検知）: `onLogoutCleanup` を `onSubjectLeave` へ改名（SG-R18）。
 * 401 は [ApiException.Unauthorized] 型で受け、一時障害とは区別する（android-contract-12）。
 */
class AuthViewModelTest {

    private val user = UserResponse(username = "u", role = "member", displayName = "U")
    private val preferences = PreferencesResponse(
        defaultDifficulty = "ielts_7",
        defaultPlaybackSpeed = 1.5,
        digestEnabled = true,
        digestArticleCount = 5,
    )

    /** 保存失敗時のエラー文言（android-contract-16, CI-S0-3。spec §3 wording）。 */
    private val sessionSaveErrorMessage = "ログイン状態を端末に保存できませんでした。もう一度お試しください"

    /** テスト対象の生成を共通化する。dispatcher は runTest の testScheduler に紐付ける。 */
    private fun TestScope.newViewModel(
        apiClient: ApiClient = FakeApiClient(),
        sessionStore: SessionStore = InMemorySessionStore(),
        onSubjectLeave: (Subject) -> Unit = {},
        onSessionRestored: suspend () -> Unit = {},
        onAuthenticated: () -> Unit = {},
        onSubjectResolved: (Subject?) -> Unit = {},
    ): AuthViewModel =
        AuthViewModel(
            authSession = AuthSession(
                authApi = AuthApiAdapter(apiClient),
                sessionStore = sessionStore,
                dispatcher = StandardTestDispatcher(testScheduler),
                onSubjectLeave = onSubjectLeave,
                onSessionRestored = onSessionRestored,
                onAuthenticated = onAuthenticated,
                onSubjectResolved = onSubjectResolved,
            ),
        )

    private fun userWithId(userId: String?, username: String = "u") =
        UserResponse(username = username, role = "member", displayName = "U", userId = userId)

    /** 起点を `Authenticated` にする（`refreshAuth()` を先に呼ぶ。設定の取り込みは hook の先なので、ここでは行わない）。 */
    private suspend fun TestScope.authenticatedViewModel(
        userId: String? = "u-1",
        username: String = "u",
        sessionStore: SessionStore = InMemorySessionStore(initialToken = "token-abc"),
        onLogin: suspend (String, String) -> LoginResponse = { _, _ -> error("login is not stubbed") },
        onLogout: suspend (String) -> Unit = {},
        onSubjectLeave: (Subject) -> Unit = {},
        onAuthenticated: () -> Unit = {},
        onSubjectResolved: (Subject?) -> Unit = {},
    ): AuthViewModel {
        val viewModel = newViewModel(
            apiClient = FakeApiClient(
                onLogin = onLogin,
                onLogout = onLogout,
                onMe = { userWithId(userId, username) },
            ),
            sessionStore = sessionStore,
            onSubjectLeave = onSubjectLeave,
            onAuthenticated = onAuthenticated,
            onSubjectResolved = onSubjectResolved,
        )
        viewModel.refreshAuth()
        return viewModel
    }

    private val cleanupStepNames =
        listOf("playback_stop", "download_cancel", "audio_cache", "preferences", "fcm_local_reset")

    /** 実物の [SubjectCleanup]。scope は runTest の scheduler に載せる（観測は `advanceUntilIdle()` の後）。 */
    private fun TestScope.newCleanup(steps: List<CleanupStep>): SubjectCleanup =
        SubjectCleanup(steps, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))

    /** 5 手順の double。各手順は `pauseMillis` だけ待ってから `calls` へ（手順名, 離脱主体）を記録し、`failFirst` の名前は 1 回目だけ記録後に失敗する。 */
    private fun recordingSteps(
        calls: MutableList<Pair<String, Subject>>,
        pauseMillis: Long = 0,
        failFirst: Set<String> = emptySet(),
    ): List<CleanupStep> {
        val pendingFailures = failFirst.toMutableSet()
        return cleanupStepNames.map { name ->
            CleanupStep(name) { subject ->
                if (pauseMillis > 0) delay(pauseMillis)
                calls += name to subject
                if (pendingFailures.remove(name)) throw RuntimeException("$name failed")
            }
        }
    }

    @Test
    fun login成功でトークン保存しAuthenticatedになる() = runTest {
        val sessionStore = InMemorySessionStore(initialToken = null)
        val loginResponse = LoginResponse(token = "new-token", user = user)
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onLogin = { _, _ -> loginResponse }),
            sessionStore = sessionStore,
        )

        viewModel.login("u", "p")

        assertEquals("new-token", sessionStore.load())
        assertEquals(AuthState.Authenticated(user.toAccountUser()), viewModel.authState.value)
        assertNull(viewModel.loginErrorMessage.value)
    }

    @Test
    fun `shared-leave-05 unauthorized_loginがUnauthorizedを受けたらユーザーIDまたはパスワードが正しくありませんと表示する`() = runTest {
        // verifies: android-contract-23（既存 :179 の置換。leakage guard: AuthViewModel は HttpError.code を比較しない）
        val sessionStore = InMemorySessionStore(initialToken = null)
        var subjectLeaveCalled = false
        var authenticatedCalled = false
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onLogin = { _, _ -> throw ApiException.Unauthorized() }),
            sessionStore = sessionStore,
            onSubjectLeave = { subjectLeaveCalled = true },
            onAuthenticated = { authenticatedCalled = true },
        )

        viewModel.login("u", "wrong-password")

        assertEquals("ユーザーIDまたはパスワードが正しくありません", viewModel.loginErrorMessage.value)
        assertEquals(AuthState.Unknown, viewModel.authState.value)
        assertNull(sessionStore.load())
        assertFalse(subjectLeaveCalled)
        assertFalse(authenticatedCalled)
    }

    @Test
    fun http403_loginがHttpError403を受けたら汎用文言を表示する() = runTest {
        // verifies: android-contract-23（401 以外の HttpError は専用文言にならない）
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onLogin = { _, _ -> throw ApiException.HttpError(403) }),
        )

        viewModel.login("u", "p")

        assertEquals("ログインに失敗しました。接続設定を確認してください", viewModel.loginErrorMessage.value)
    }

    @Test
    fun login429なら汎用文言を表示する() = runTest {
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onLogin = { _, _ -> throw ApiException.RateLimited(retryAfterSeconds = 30) }),
        )

        viewModel.login("u", "p")

        assertEquals("ログインに失敗しました。接続設定を確認してください", viewModel.loginErrorMessage.value)
    }

    @Test
    fun login空入力なら文言を表示しAPIを呼ばない() = runTest {
        val apiClient = FakeApiClient()
        val viewModel = newViewModel(apiClient = apiClient)

        viewModel.login("", "")

        assertEquals("ユーザーIDとパスワードを入力してください", viewModel.loginErrorMessage.value)
        assertEquals(0, apiClient.loginCallCount)
    }

    @Test
    fun login_save_fail_saveが失敗したらAuthenticatedにならずエラー文言を表示する() = runTest {
        // verifies: android-contract-16
        val loginResponse = LoginResponse(token = "new-token", user = user)
        var authenticatedCalled = false
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onLogin = { _, _ -> loginResponse }),
            sessionStore = InMemorySessionStore(saveFails = true),
            onAuthenticated = { authenticatedCalled = true },
        )

        viewModel.login("u", "p")

        assertEquals(AuthState.Unknown, viewModel.authState.value)
        assertEquals(sessionSaveErrorMessage, viewModel.loginErrorMessage.value)
        assertFalse(authenticatedCalled)
    }

    @Test
    fun completePasskeyLoginはsave失敗時にAuthenticatedにならずエラー文言を表示する() = runTest {
        // verifies: CI-S0-3
        val loginResponse = LoginResponse(token = "passkey-token", user = user)
        var authenticatedCalled = false
        val viewModel = newViewModel(
            sessionStore = InMemorySessionStore(saveFails = true),
            onAuthenticated = { authenticatedCalled = true },
        )

        viewModel.completePasskeyLogin(loginResponse.toIssuedSession())

        assertEquals(AuthState.Unknown, viewModel.authState.value)
        assertEquals(sessionSaveErrorMessage, viewModel.loginErrorMessage.value)
        assertFalse(authenticatedCalled)
    }

    @Test
    fun completePasskeyLoginでトークン保存しAuthenticatedになる() = runTest {
        val sessionStore = InMemorySessionStore(initialToken = null)
        val loginResponse = LoginResponse(token = "passkey-token", user = user)
        val viewModel = newViewModel(sessionStore = sessionStore)

        viewModel.completePasskeyLogin(loginResponse.toIssuedSession())

        assertEquals("passkey-token", sessionStore.load())
        assertEquals(AuthState.Authenticated(user.toAccountUser()), viewModel.authState.value)
        assertNull(viewModel.loginErrorMessage.value)
    }

    @Test
    fun `cancel_during_cleanup_logoutの呼出元が終わった後も後始末は最後まで走りcleanupIncompleteが確定する`() = runTest {
        // verifies: CI-S0-10, android-contract-15
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        val calls = mutableListOf<Pair<String, Subject>>()
        val cleanup = newCleanup(recordingSteps(calls, pauseMillis = 1_000))
        val viewModel = authenticatedViewModel(sessionStore = sessionStore, onSubjectLeave = cleanup::start)

        val job = launch { viewModel.logout() }
        runCurrent()
        job.cancel()
        advanceUntilIdle()

        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
        assertNull(sessionStore.load())
        assertEquals(cleanupStepNames, calls.map { it.first })
        assertNull(cleanup.cleanupIncomplete.value)
    }

    @Test
    fun concurrent_success_then_transient_failure_並行refreshで成功後に一時障害が完了してもAuthenticatedにlastFailureを立てない() = runTest {
        // verifies: android-contract-12（third_vote_completion GPT-6 反証1: 並行 refreshAuth の
        // 成功→一時障害という順序で、開始時スナップショットの wasAuthenticated を使うと
        // Authenticated かつ lastFailure != null になる。判定を書込みと同じ sessionLock 区間内の
        // 現在値に差し替えて閉じたことを確認する）。
        var callCount = 0
        val viewModel = newViewModel(
            apiClient = FakeApiClient(
                onMe = {
                    callCount++
                    if (callCount == 1) {
                        // A: 先に成功して Authenticated へ遷移する。
                        delay(1)
                        user
                    } else {
                        // B: A が Authenticated へ遷移した後に一時障害で完了する。
                        delay(2)
                        throw ApiException.NetworkError(RuntimeException("offline"))
                    }
                },
                onFetchPreferences = { preferences },
            ),
            sessionStore = InMemorySessionStore(initialToken = "token-abc"),
        )

        launch { viewModel.refreshAuth() }
        launch { viewModel.refreshAuth() }
        advanceUntilIdle()

        assertEquals(AuthState.Authenticated(user.toAccountUser()), viewModel.authState.value)
        assertNull(viewModel.lastFailure.value)
    }

    @Test
    fun `shared-leave-01 android-test-14 logoutは後始末の完了を待たずにUnauthenticatedになり手順は5つの名前の順に走る`() = runTest {
        // verifies: android-contract-14
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        val calls = mutableListOf<Pair<String, Subject>>()
        val cleanup = newCleanup(recordingSteps(calls, pauseMillis = 1_000))
        val viewModel = authenticatedViewModel(sessionStore = sessionStore, onSubjectLeave = cleanup::start)

        launch { viewModel.logout() }
        runCurrent()

        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
        assertNull(sessionStore.load())
        assertTrue("手順はまだ完了していない", calls.size < cleanupStepNames.size)

        advanceUntilIdle()

        assertEquals(cleanupStepNames, calls.map { it.first })
        assertEquals(List(5) { Subject("u-1", "u") }, calls.map { it.second })
        assertNull(cleanup.cleanupIncomplete.value)
    }

    @Test
    fun `shared-leave-01 決定14 logoutは破棄前に捕捉したトークンをapiClientのlogoutへ渡し完了を待たずにUnauthenticatedになる`() = runTest {
        val sent = mutableListOf<String>()
        val viewModel = authenticatedViewModel(onLogout = { token -> delay(1_000); sent += token })

        val job = launch { viewModel.logout() }
        runCurrent()

        assertTrue("logout は送信の完了を待たずに戻る", job.isCompleted)
        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
        assertTrue(sent.isEmpty())

        advanceUntilIdle()

        assertEquals(listOf("token-abc"), sent)
    }

    @Test
    fun `shared-leave-04 android-test-15 手順の1つが失敗しても残りは実行されcleanupIncompleteにpartsが出て再ログイン後のlogoutの再実行で全成功ならnullに戻る`() = runTest {
        // verifies: android-contract-15
        val calls = mutableListOf<Pair<String, Subject>>()
        val cleanup = newCleanup(recordingSteps(calls, failFirst = setOf("audio_cache")))
        val loginResponse = LoginResponse(token = "token-2", user = userWithId("u-1"))
        val viewModel = authenticatedViewModel(onLogin = { _, _ -> loginResponse }, onSubjectLeave = cleanup::start)

        viewModel.logout()
        advanceUntilIdle()

        assertEquals(CleanupIncomplete(setOf("audio_cache")), cleanup.cleanupIncomplete.value)
        assertEquals(cleanupStepNames, calls.map { it.first })

        viewModel.login("u", "p")
        viewModel.logout()
        advanceUntilIdle()

        assertEquals(cleanupStepNames + cleanupStepNames, calls.map { it.first })
        assertNull(cleanup.cleanupIncomplete.value)
    }

    @Test
    fun `shared-leave-07 保存に失敗したloginはAのままで離脱を起動しない`() = runTest {
        var leaveCount = 0
        val userA = userWithId("u-1")
        val store = object : SessionStore {
            private val backing = InMemorySessionStore(initialToken = "token-abc")
            var saveFails = false
            override fun save(token: String): Boolean = if (saveFails) false else backing.save(token)
            override fun load(): String? = backing.load()
            override fun clear() = backing.clear()
        }
        val viewModel = authenticatedViewModel(
            userId = "u-1",
            sessionStore = store,
            onLogin = { _, _ -> LoginResponse(token = "token-b", user = userWithId("u-2", "bob")) },
            onSubjectLeave = { leaveCount++ },
        )
        store.saveFails = true

        viewModel.login("bob", "p")
        advanceUntilIdle()

        assertEquals(AuthState.Authenticated(userA.toAccountUser()), viewModel.authState.value)
        assertEquals(0, leaveCount)
        assertEquals(sessionSaveErrorMessage, viewModel.loginErrorMessage.value)
    }

    @Test
    fun `android-dec-84 completePasskeyLoginの保存失敗は保存失敗の文言を出し次のEstablishedでnullに戻る`() = runTest {
        val store = object : SessionStore {
            private val backing = InMemorySessionStore(initialToken = null)
            var saveFails = true
            override fun save(token: String): Boolean = if (saveFails) false else backing.save(token)
            override fun load(): String? = backing.load()
            override fun clear() = backing.clear()
        }
        val viewModel = newViewModel(sessionStore = store)
        val issued = LoginResponse(token = "passkey-token", user = user).toIssuedSession()

        viewModel.completePasskeyLogin(issued)
        assertEquals(sessionSaveErrorMessage, viewModel.loginErrorMessage.value)

        store.saveFails = false
        viewModel.completePasskeyLogin(issued)
        assertNull(viewModel.loginErrorMessage.value)
    }

    @Test
    fun `android-dec-84 loginの失敗の後に成功するとloginErrorMessageがnullに戻る`() = runTest {
        var callCount = 0
        val viewModel = newViewModel(
            apiClient = FakeApiClient(
                onLogin = { _, _ ->
                    callCount++
                    if (callCount == 1) throw ApiException.Unauthorized() else LoginResponse(token = "new-token", user = user)
                },
            ),
        )

        viewModel.login("u", "wrong-password")
        assertEquals("ユーザーIDまたはパスワードが正しくありません", viewModel.loginErrorMessage.value)

        viewModel.login("u", "p")
        assertNull(viewModel.loginErrorMessage.value)
    }

    @Test
    fun `android-dec-86 AuthViewModelのapplyProfileUpdateはAuthSessionへ中継される`() = runTest {
        val viewModel = authenticatedViewModel(userId = "u-1")

        viewModel.applyProfileUpdate("New")

        val updated = (viewModel.authState.value as AuthState.Authenticated).user
        assertEquals("New", updated.displayName)
        assertEquals(user.username, updated.username)
    }

    @Test
    fun `android-dec-84 確立の後のhookが投げたloginはAuthenticatedのまま例外の型に応じた文言を出す`() = runTest {
        val cases = listOf<Pair<ApiException, String>>(
            ApiException.NetworkError(RuntimeException("offline")) to "ログインに失敗しました。接続設定を確認してください",
            ApiException.Unauthorized() to "ユーザーIDまたはパスワードが正しくありません",
        )
        cases.forEach { (failure, message) ->
            val viewModel = newViewModel(
                apiClient = FakeApiClient(onLogin = { _, _ -> LoginResponse(token = "new-token", user = user) }),
                onAuthenticated = { throw failure },
            )

            viewModel.login("u", "p")

            assertTrue("$failure", viewModel.authState.value is AuthState.Authenticated)
            assertEquals("$failure", message, viewModel.loginErrorMessage.value)
        }
    }
}
