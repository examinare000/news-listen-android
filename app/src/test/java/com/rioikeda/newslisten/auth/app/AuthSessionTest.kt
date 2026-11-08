package com.rioikeda.newslisten.auth.app

import com.rioikeda.newslisten.auth.AuthState
import com.rioikeda.newslisten.auth.CleanupIncomplete
import com.rioikeda.newslisten.auth.CleanupStep
import com.rioikeda.newslisten.auth.FakeApiClient
import com.rioikeda.newslisten.auth.Subject
import com.rioikeda.newslisten.auth.SubjectCleanup
import com.rioikeda.newslisten.auth.domain.AccountUser
import com.rioikeda.newslisten.auth.domain.LoginOutcome
import com.rioikeda.newslisten.auth.domain.Role
import com.rioikeda.newslisten.auth.domain.SubjectId
import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.core.Difficulty
import com.rioikeda.newslisten.core.PlaybackSpeed
import com.rioikeda.newslisten.model.LoginResponse
import com.rioikeda.newslisten.model.PreferencesResponse
import com.rioikeda.newslisten.model.UserResponse
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.AuthApiAdapter
import com.rioikeda.newslisten.network.InMemorySessionStore
import com.rioikeda.newslisten.network.SessionStore
import com.rioikeda.newslisten.network.toAccountUser
import com.rioikeda.newslisten.network.toIssuedSession
import com.rioikeda.newslisten.preferences.InMemoryPreferencesStore
import com.rioikeda.newslisten.preferences.domain.WeeklyGoal
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AuthSession] の挙動検証（session owner。遷移・失効・離脱の導出・hook の発火）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/AppState.swift:130-178（refreshAuth/refreshPreferences/logout）、
 * ios/NewsListenApp/NewsListenApp/Auth/LoginViewModel.swift（ログインのエラー文言）のミラー。
 *
 * 保存済みトークンで認証が確立したとき、設定の取り込みは hook `onSessionRestored` が担う
 * （AuthSession は設定を知らない。取り込み自体の検証は PreferencesSyncTest）。
 *
 * AuthViewModel から移したテストは、assert 行の字句を保つため、テスト対象のローカル変数名を `viewModel` のままにしている
 * （型は [AuthSession]）。
 *
 * android-slice-auth-01（認証失効の型分離と検知）: `onLogoutCleanup` を `onSubjectLeave` へ改名（SG-R18）。
 * 401 は [ApiException.Unauthorized] 型で受け、一時障害とは区別する（android-contract-12）。
 */
class AuthSessionTest {

    private val user = UserResponse(username = "u", role = "member", displayName = "U")
    private val preferences = PreferencesResponse(
        defaultDifficulty = "ielts_7",
        defaultPlaybackSpeed = 1.5,
        digestEnabled = true,
        digestArticleCount = 5,
    )

    /** テスト対象の生成を共通化する。dispatcher は runTest の testScheduler に紐付ける。 */
    private fun TestScope.newSession(
        apiClient: ApiClient = FakeApiClient(),
        sessionStore: SessionStore = InMemorySessionStore(),
        onSubjectLeave: (Subject) -> Unit = {},
        onSessionRestored: suspend () -> Unit = {},
        onAuthenticated: () -> Unit = {},
        onSubjectResolved: (Subject?) -> Unit = {},
    ): AuthSession =
        AuthSession(
            authApi = AuthApiAdapter(apiClient),
            sessionStore = sessionStore,
            dispatcher = StandardTestDispatcher(testScheduler),
            onSubjectLeave = onSubjectLeave,
            onSessionRestored = onSessionRestored,
            onAuthenticated = onAuthenticated,
            onSubjectResolved = onSubjectResolved,
        )

    private fun userWithId(userId: String?, username: String = "u") =
        UserResponse(username = username, role = "member", displayName = "U", userId = userId)

    /** 起点を `Authenticated` にする（`refreshAuth()` を先に呼ぶ。設定の取り込みは hook の先なので、ここでは行わない）。 */
    private suspend fun TestScope.authenticatedSession(
        userId: String? = "u-1",
        username: String = "u",
        sessionStore: SessionStore = InMemorySessionStore(initialToken = "token-abc"),
        onLogin: suspend (String, String) -> LoginResponse = { _, _ -> error("login is not stubbed") },
        onLogout: suspend (String) -> Unit = {},
        onSubjectLeave: (Subject) -> Unit = {},
        onAuthenticated: () -> Unit = {},
        onSubjectResolved: (Subject?) -> Unit = {},
    ): AuthSession {
        val viewModel = newSession(
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

    // --- refreshAuth（android-contract-12, android-test-12） ---

    @Test
    fun トークン未保存ならUnauthenticatedになる() = runTest {
        val viewModel = newSession(sessionStore = InMemorySessionStore(initialToken = null))

        viewModel.refreshAuth()

        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
    }

    @Test
    fun トークンありでme成功ならAuthenticatedになりuserを保持する() = runTest {
        val viewModel = newSession(
            apiClient = FakeApiClient(onMe = { user }, onFetchPreferences = { preferences }),
            sessionStore = InMemorySessionStore(initialToken = "token-abc"),
        )

        viewModel.refreshAuth()

        assertEquals(AuthState.Authenticated(user.toAccountUser()), viewModel.authState.value)
    }

    @Test
    fun unauthorized_トークンありでmeがUnauthorizedならトークン破棄してUnauthenticatedになる() = runTest {
        // verifies: android-contract-12
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        var subjectLeaveCalled = false
        val viewModel = newSession(
            apiClient = FakeApiClient(onMe = { throw ApiException.Unauthorized() }),
            sessionStore = sessionStore,
            onSubjectLeave = { subjectLeaveCalled = true },
        )

        viewModel.refreshAuth()

        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
        assertNull(sessionStore.load())
        assertNull(viewModel.lastFailure.value)
        assertFalse("Unknown からの失効は離脱でなく起動時回収が拾うので後始末を呼ばない", subjectLeaveCalled)
    }

    @Test
    fun `shared-leave-03 network_トークンありでmeがNetworkErrorならUnknownのままトークンを保持しlastFailureを立てる`() = runTest {
        // verifies: android-contract-12（既存 :95 の反転。一時障害でトークンを破棄しない仕様変更）
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        val viewModel = newSession(
            apiClient = FakeApiClient(onMe = { throw ApiException.NetworkError(RuntimeException("offline")) }),
            sessionStore = sessionStore,
        )

        viewModel.refreshAuth()

        assertEquals(AuthState.Unknown, viewModel.authState.value)
        assertEquals("token-abc", sessionStore.load())
        assertTrue(viewModel.lastFailure.value is ApiException.NetworkError)
    }

    @Test
    fun `shared-leave-03 http500_トークンありでmeがHttpError500ならUnknownのままトークンを保持しlastFailureを立てる`() = runTest {
        // verifies: android-contract-12
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        val viewModel = newSession(
            apiClient = FakeApiClient(onMe = { throw ApiException.HttpError(500) }),
            sessionStore = sessionStore,
        )

        viewModel.refreshAuth()

        assertEquals(AuthState.Unknown, viewModel.authState.value)
        assertEquals("token-abc", sessionStore.load())
        val failure = viewModel.lastFailure.value
        assertTrue(failure is ApiException.HttpError)
        assertEquals(500, (failure as ApiException.HttpError).code)
    }

    @Test
    fun `shared-leave-03 decoding_トークンありでmeがDecodingErrorならUnknownのままトークンを保持しlastFailureを立てる`() = runTest {
        // verifies: android-contract-12
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        val viewModel = newSession(
            apiClient = FakeApiClient(onMe = { throw ApiException.DecodingError(RuntimeException("bad json")) }),
            sessionStore = sessionStore,
        )

        viewModel.refreshAuth()

        assertEquals(AuthState.Unknown, viewModel.authState.value)
        assertEquals("token-abc", sessionStore.load())
        assertTrue(viewModel.lastFailure.value is ApiException.DecodingError)
    }

    @Test
    fun rerun_1回目はNetworkErrorで2回目は成功しAuthenticatedになりlastFailureが消える() = runTest {
        // verifies: android-contract-12
        var callCount = 0
        val viewModel = newSession(
            apiClient = FakeApiClient(
                onMe = {
                    callCount++
                    if (callCount == 1) throw ApiException.NetworkError(RuntimeException("offline")) else user
                },
                onFetchPreferences = { preferences },
            ),
            sessionStore = InMemorySessionStore(initialToken = "token-abc"),
        )

        viewModel.refreshAuth()
        assertEquals(AuthState.Unknown, viewModel.authState.value)
        assertTrue(viewModel.lastFailure.value is ApiException.NetworkError)

        viewModel.refreshAuth()
        assertEquals(AuthState.Authenticated(user.toAccountUser()), viewModel.authState.value)
        assertNull(viewModel.lastFailure.value)
    }

    @Test
    fun rotation_Authenticated中に再実行したrefreshが一時障害でも状態を変えない() = runTest {
        // verifies: android-contract-12（F-2: 画面回転での LaunchedEffect 再実行）
        var callCount = 0
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        val viewModel = newSession(
            apiClient = FakeApiClient(
                onMe = {
                    callCount++
                    if (callCount == 1) user else throw ApiException.NetworkError(RuntimeException("offline"))
                },
                onFetchPreferences = { preferences },
            ),
            sessionStore = sessionStore,
        )

        viewModel.refreshAuth()
        viewModel.refreshAuth()

        assertEquals(AuthState.Authenticated(user.toAccountUser()), viewModel.authState.value)
        assertEquals("token-abc", sessionStore.load())
        assertNull(viewModel.lastFailure.value)
    }

    // --- onSessionRestored（保存済みトークンで確立した事実の通知） ---

    @Test
    fun `android-dec-78 保存済みトークンでme成功するとonSessionRestoredがonAuthenticatedより前に1回呼ばれる`() = runTest {
        // verifies: android-dec-78
        val events = mutableListOf<String>()
        val viewModel = newSession(
            apiClient = FakeApiClient(onMe = { user }),
            sessionStore = InMemorySessionStore(initialToken = "token-abc"),
            onSessionRestored = { events += "restored" },
            onAuthenticated = { events += "authenticated" },
        )

        viewModel.refreshAuth()

        assertEquals(listOf("restored", "authenticated"), events)
    }

    @Test
    fun `android-dec-78 loginの確立ではonSessionRestoredを呼ばずonAuthenticatedだけが呼ばれる`() = runTest {
        // verifies: android-dec-78
        var restoredCallCount = 0
        var authenticatedCallCount = 0
        val viewModel = newSession(
            apiClient = FakeApiClient(onLogin = { _, _ -> LoginResponse(token = "new-token", user = user) }),
            sessionStore = InMemorySessionStore(initialToken = null),
            onSessionRestored = { restoredCallCount++ },
            onAuthenticated = { authenticatedCallCount++ },
        )

        viewModel.login("u", "p")

        assertEquals(AuthState.Authenticated(user.toAccountUser()), viewModel.authState.value)
        assertEquals(0, restoredCallCount)
        assertEquals(1, authenticatedCallCount)
    }

    // --- logout ---

    @Test
    fun `logoutはAPI失敗でもトークンを破棄しUnauthenticatedになる`() = runTest {
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        val viewModel = newSession(
            apiClient = FakeApiClient(onLogout = { throw ApiException.NetworkError(RuntimeException("offline")) }),
            sessionStore = sessionStore,
        )

        viewModel.logout()
        advanceUntilIdle()

        assertNull(sessionStore.load())
        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
    }

    // --- logout: 離脱の導出と後始末（ADR-104 決定 1・14。離脱は直前が Authenticated の遷移だけで起動する） ---

    @Test
    fun `shared-leave-01 android-test-14 logoutはAuthenticatedの離脱主体でonSubjectLeaveを1回呼ぶ`() = runTest {
        // verifies: android-contract-14
        val left = mutableListOf<Subject>()
        val viewModel = authenticatedSession(userId = "u-1", onSubjectLeave = { left += it })

        viewModel.logout()
        advanceUntilIdle()

        assertEquals(listOf(Subject("u-1", "u")), left)
    }

    @Test
    fun `logoutはonSubjectLeaveが同期で例外を投げてもトークンを破棄しUnauthenticatedになりapiClientのlogoutを送る`() = runTest {
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        val sent = mutableListOf<String>()
        val viewModel = authenticatedSession(
            sessionStore = sessionStore,
            onLogout = { sent += it },
            onSubjectLeave = { throw RuntimeException("cache cleanup failed") },
        )

        viewModel.logout()
        advanceUntilIdle()

        assertNull(sessionStore.load())
        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
        assertEquals(listOf("token-abc"), sent)
    }

    @Test
    fun `Unknownからのloginでは主体の離脱を起動しない`() = runTest {
        var subjectLeaveCalled = false
        val loginResponse = LoginResponse(token = "new-token", user = user)
        val viewModel = newSession(
            apiClient = FakeApiClient(onLogin = { _, _ -> loginResponse }),
            onSubjectLeave = { subjectLeaveCalled = true },
        )

        viewModel.login("u", "p")

        assertFalse(subjectLeaveCalled)
    }

    // --- onAuthenticated（フェーズ9・FCM トークン登録フック） ---

    @Test
    fun refreshAuthでAuthenticatedになるとonAuthenticatedが呼ばれる() = runTest {
        var authenticatedCalled = false
        val viewModel = newSession(
            apiClient = FakeApiClient(onMe = { user }, onFetchPreferences = { preferences }),
            sessionStore = InMemorySessionStore(initialToken = "token-abc"),
            onAuthenticated = { authenticatedCalled = true },
        )

        viewModel.refreshAuth()

        assertTrue(authenticatedCalled)
    }

    @Test
    fun refreshAuthでUnauthenticatedのままならonAuthenticatedは呼ばれない() = runTest {
        var authenticatedCalled = false
        val viewModel = newSession(
            sessionStore = InMemorySessionStore(initialToken = null),
            onAuthenticated = { authenticatedCalled = true },
        )

        viewModel.refreshAuth()

        assertFalse(authenticatedCalled)
    }

    @Test
    fun login成功でonAuthenticatedが呼ばれる() = runTest {
        var authenticatedCalled = false
        val loginResponse = LoginResponse(token = "new-token", user = user)
        val viewModel = newSession(
            apiClient = FakeApiClient(onLogin = { _, _ -> loginResponse }),
            onAuthenticated = { authenticatedCalled = true },
        )

        viewModel.login("u", "p")

        assertTrue(authenticatedCalled)
    }

    @Test
    fun login失敗ならonAuthenticatedは呼ばれない() = runTest {
        var authenticatedCalled = false
        val viewModel = newSession(
            apiClient = FakeApiClient(onLogin = { _, _ -> throw ApiException.Unauthorized() }),
            onAuthenticated = { authenticatedCalled = true },
        )

        viewModel.login("u", "wrong-password")

        assertFalse(authenticatedCalled)
    }

    @Test
    fun logoutではonAuthenticatedは呼ばれない() = runTest {
        var authenticatedCalled = false
        val viewModel = newSession(onAuthenticated = { authenticatedCalled = true })

        viewModel.logout()

        assertFalse(authenticatedCalled)
    }

    @Test
    fun completePasskeyLoginでonAuthenticatedが呼ばれる() = runTest {
        var authenticatedCalled = false
        val loginResponse = LoginResponse(token = "passkey-token", user = user)
        val viewModel = newSession(onAuthenticated = { authenticatedCalled = true })

        viewModel.completePasskeyLogin(loginResponse.toIssuedSession())

        assertTrue(authenticatedCalled)
    }

    // --- applyProfileUpdate（フェーズ11 P11 T2: 表示名更新のAuthState反映） ---

    @Test
    fun applyProfileUpdateはAuthenticated状態でauthStateのuserを更新する() = runTest {
        val viewModel = newSession(
            apiClient = FakeApiClient(onMe = { user }, onFetchPreferences = { preferences }),
            sessionStore = InMemorySessionStore(initialToken = "token-abc"),
        )
        viewModel.refreshAuth()
        val updatedUser = user.copy(displayName = "新しい表示名")

        viewModel.applyProfileUpdate(updatedUser.displayName)

        assertEquals(AuthState.Authenticated(updatedUser.toAccountUser()), viewModel.authState.value)
    }

    @Test
    fun applyProfileUpdateは非Authenticatedならno_opになる() = runTest {
        val viewModel = newSession(sessionStore = InMemorySessionStore(initialToken = null))
        viewModel.refreshAuth()
        val updatedUser = user.copy(displayName = "新しい表示名")

        viewModel.applyProfileUpdate(updatedUser.displayName)

        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
    }

    // --- onUnauthorized とセッション書込みの排他（CI-S0-5, T-S0-5） ---

    @Test
    fun revocation_付与したトークンが現在値ならUnauthenticatedにしてトークンを破棄する() = runTest {
        // verifies: CI-S0-5
        val sessionStore = InMemorySessionStore(initialToken = "t")
        var subjectLeaveCalled = false
        val viewModel = newSession(
            sessionStore = sessionStore,
            onSubjectLeave = { subjectLeaveCalled = true },
        )

        viewModel.onUnauthorized("t")

        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
        assertNull(sessionStore.load())
        assertNull(viewModel.lastFailure.value)
        assertFalse("Unknown からの失効は離脱でなく起動時回収が拾うので後始末を呼ばない", subjectLeaveCalled)
    }

    @Test
    fun stale_notice_付与したトークンが現在値でなければ何も変えない() = runTest {
        // verifies: CI-S0-5
        val sessionStore = InMemorySessionStore(initialToken = null)
        val loginResponse = LoginResponse(token = "t2", user = user)
        val viewModel = newSession(
            apiClient = FakeApiClient(onLogin = { _, _ -> loginResponse }),
            sessionStore = sessionStore,
        )
        viewModel.login("u", "p")

        viewModel.onUnauthorized("t1")

        assertEquals(AuthState.Authenticated(user.toAccountUser()), viewModel.authState.value)
        assertEquals("t2", sessionStore.load())
    }

    @Test
    fun refresh_superseded_success_me成功の途中で失効するとAuthenticatedを復活させない() = runTest {
        // verifies: CI-S0-5
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        var authenticatedCalled = false
        lateinit var viewModel: AuthSession
        viewModel = newSession(
            apiClient = FakeApiClient(
                onMe = {
                    // me の途中で失効したことを模す。
                    viewModel.onUnauthorized("token-abc")
                    user
                },
                onFetchPreferences = { preferences },
            ),
            sessionStore = sessionStore,
            onAuthenticated = { authenticatedCalled = true },
        )

        viewModel.refreshAuth()

        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
        assertNull(sessionStore.load())
        assertFalse(authenticatedCalled)
    }

    @Test
    fun refresh_superseded_401_me401の途中で再ログインすると新しいトークンを消さない() = runTest {
        // verifies: CI-S0-5
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        val viewModel = newSession(
            apiClient = FakeApiClient(
                onMe = {
                    // me の途中で再ログインしたことを模す。
                    sessionStore.save("t2")
                    throw ApiException.Unauthorized()
                },
            ),
            sessionStore = sessionStore,
        )

        viewModel.refreshAuth()

        assertEquals("t2", sessionStore.load())
    }

    @Test
    fun profile_after_revoke_失効後にapplyProfileUpdateしてもAuthenticatedへ戻らない() = runTest {
        // verifies: CI-S0-5
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        val viewModel = newSession(
            apiClient = FakeApiClient(onMe = { user }, onFetchPreferences = { preferences }),
            sessionStore = sessionStore,
        )
        viewModel.refreshAuth()
        val updatedUser = user.copy(displayName = "新しい表示名")

        viewModel.onUnauthorized("token-abc")
        viewModel.applyProfileUpdate(updatedUser.displayName)

        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
    }

    // --- logout 実行中の失効通知の抑止（CI-S0-10, T-S0-10。SG-S0-7 = (a)） ---

    @Test
    fun `notice_during_logout_トークン破棄の後に届いた失効通知は無視され後始末は1回だけ起動される`() = runTest {
        // verifies: CI-S0-10
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        var recordedState: AuthState? = null
        var recordedToken: String? = "unset"
        var leaveCount = 0
        lateinit var viewModel: AuthSession
        viewModel = authenticatedSession(
            sessionStore = sessionStore,
            onSubjectLeave = {
                leaveCount++
                viewModel.onUnauthorized("token-abc")
                recordedState = viewModel.authState.value
                recordedToken = sessionStore.load()
            },
        )

        viewModel.logout()
        advanceUntilIdle()

        assertEquals(AuthState.Unauthenticated, recordedState)
        assertNull(recordedToken)
        assertEquals(1, leaveCount)
        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
        assertNull(sessionStore.load())
    }

    @Test
    fun `notice_during_server_logout_apiClient_logoutの実行中に届いた失効通知も無視される`() = runTest {
        // verifies: CI-S0-10（server がセッションを削除した直後に並行要求が 401 を受ける経路 B の入口を塞ぐ）
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        var recordedState: AuthState? = null
        var recordedToken: String? = "unset"
        var leaveCount = 0
        lateinit var viewModel: AuthSession
        viewModel = authenticatedSession(
            sessionStore = sessionStore,
            onLogout = {
                viewModel.onUnauthorized("token-abc")
                recordedState = viewModel.authState.value
                recordedToken = sessionStore.load()
            },
            onSubjectLeave = { leaveCount++ },
        )

        viewModel.logout()
        advanceUntilIdle()

        assertEquals(AuthState.Unauthenticated, recordedState)
        assertNull(recordedToken)
        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
        assertNull(sessionStore.load())
        assertEquals(1, leaveCount)
    }

    @Test
    fun `notice_during_logout_トークン破棄の前に届いた失効通知はガードで無視され捕捉トークンで送る`() = runTest {
        // verifies: CI-S0-10（logoutsInProgress > 0 の抑止を単独で固定する。照合だけでは、破棄前の通知がトークンの一致で通ってしまう）
        val backing = InMemorySessionStore(initialToken = "token-abc")
        var armed = false
        var notified = false
        lateinit var viewModel: AuthSession
        // load() の中から 1 回だけ現在のトークンで失効通知を呼ぶ。通知の後に値を読んで返す
        // （先に読むと、ガードが無くても通知がトークン破棄の前に処理されず、検査にならない）。
        val store = object : SessionStore {
            override fun load(): String? {
                if (armed && !notified) {
                    notified = true
                    viewModel.onUnauthorized(backing.load() ?: "none")
                }
                return backing.load()
            }

            override fun save(token: String): Boolean = backing.save(token)

            override fun clear() = backing.clear()
        }
        val sent = mutableListOf<String>()
        var leaveCount = 0
        viewModel = authenticatedSession(
            sessionStore = store,
            onLogout = { sent += it },
            onSubjectLeave = { leaveCount++ },
        )
        armed = true

        viewModel.logout()
        advanceUntilIdle()

        assertEquals(1, leaveCount)
        assertEquals(listOf("token-abc"), sent)
        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
    }

    @Test
    fun `shared-leave-01 order_pin_トークン破棄とUnauthenticatedが後始末の起動より先に観測される`() = runTest {
        // verifies: CI-S0-10, android-contract-14（離脱は トークン破棄 → 未認証 → 後始末の起動 の順）
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        var recordedState: AuthState? = null
        var recordedToken: String? = "unset"
        lateinit var viewModel: AuthSession
        viewModel = authenticatedSession(
            sessionStore = sessionStore,
            onSubjectLeave = {
                recordedState = viewModel.authState.value
                recordedToken = sessionStore.load()
            },
        )

        viewModel.logout()
        advanceUntilIdle()

        assertEquals(AuthState.Unauthenticated, recordedState)
        assertNull(recordedToken)
    }

    @Test
    fun `guard_released_logoutが戻った後はガードが外れ再ログインした新しいセッションへの失効通知は処理される`() = runTest {
        // verifies: CI-S0-10（F10: 減算が finally に無いと以後の失効通知を捨て続ける欠陥を判別する）
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        val loginResponse = LoginResponse(token = "token-2", user = userWithId("u-1"))
        val viewModel = authenticatedSession(
            sessionStore = sessionStore,
            onLogin = { _, _ -> loginResponse },
        )
        viewModel.logout()
        advanceUntilIdle()
        viewModel.login("u", "p")
        assertEquals(AuthState.Authenticated(userWithId("u-1").toAccountUser()), viewModel.authState.value)

        viewModel.onUnauthorized("token-2")

        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
        assertNull(sessionStore.load())
    }

    // --- retryRefreshAuth（CI-S0-8, T-S0-8） ---

    @Test
    fun completes_without_caller_scope_呼出元のscopeが無くてもrefreshが完走する() = runTest {
        // verifies: CI-S0-8
        var restoredCallCount = 0
        var authenticatedCallCount = 0
        val viewModel = newSession(
            apiClient = FakeApiClient(onMe = { user }, onFetchPreferences = { preferences }),
            sessionStore = InMemorySessionStore(initialToken = "token-abc"),
            onSessionRestored = { restoredCallCount++ },
            onAuthenticated = { authenticatedCallCount++ },
        )

        viewModel.retryRefreshAuth()
        advanceUntilIdle()

        assertEquals(AuthState.Authenticated(user.toAccountUser()), viewModel.authState.value)
        assertNull(viewModel.lastFailure.value)
        assertEquals(1, restoredCallCount)
        assertEquals(1, authenticatedCallCount)
    }

    @Test
    fun dedup_while_running_実行中の再呼出は無視され1回だけ実行される() = runTest {
        // verifies: CI-S0-8
        var meCallCount = 0
        var authenticatedCallCount = 0
        val viewModel = newSession(
            apiClient = FakeApiClient(
                onMe = {
                    meCallCount++
                    delay(1_000)
                    user
                },
                onFetchPreferences = { preferences },
            ),
            sessionStore = InMemorySessionStore(initialToken = "token-abc"),
            onAuthenticated = { authenticatedCallCount++ },
        )

        viewModel.retryRefreshAuth()
        runCurrent()
        viewModel.retryRefreshAuth()
        advanceUntilIdle()

        assertEquals(1, meCallCount)
        assertEquals(1, authenticatedCallCount)
    }

    @Test
    fun rerun_after_failure_失敗後に再試行すると成功する() = runTest {
        // verifies: CI-S0-8, android-contract-12, CI-S0-6
        var callCount = 0
        val viewModel = newSession(
            apiClient = FakeApiClient(
                onMe = {
                    callCount++
                    if (callCount == 1) throw ApiException.NetworkError(RuntimeException("offline")) else user
                },
                onFetchPreferences = { preferences },
            ),
            sessionStore = InMemorySessionStore(initialToken = "token-abc"),
        )

        viewModel.retryRefreshAuth()
        advanceUntilIdle()
        assertEquals(AuthState.Unknown, viewModel.authState.value)
        assertTrue(viewModel.lastFailure.value is ApiException.NetworkError)

        viewModel.retryRefreshAuth()
        advanceUntilIdle()
        assertEquals(AuthState.Authenticated(user.toAccountUser()), viewModel.authState.value)
        assertNull(viewModel.lastFailure.value)
        assertEquals(2, callCount)
    }

    // --- 起動時回収の発火（onSubjectResolved。shared-leave-08・09・10 / android-dec-48） ---

    @Test
    fun `shared-leave-08 Unknownの間はonSubjectResolvedを呼ばず再試行でmeが成功すると1回呼ぶ`() = runTest {
        var meFails = true
        val resolved = mutableListOf<Subject?>()
        val viewModel = newSession(
            apiClient = FakeApiClient(
                onMe = { if (meFails) throw ApiException.NetworkError(RuntimeException("offline")) else userWithId("u-1") },
                onFetchPreferences = { preferences },
            ),
            sessionStore = InMemorySessionStore(initialToken = "token-abc"),
            onSubjectResolved = { resolved += it },
        )

        viewModel.refreshAuth()

        assertEquals(AuthState.Unknown, viewModel.authState.value)
        assertEquals(0, resolved.size)

        meFails = false
        viewModel.retryRefreshAuth()
        advanceUntilIdle()

        assertTrue(viewModel.authState.value is AuthState.Authenticated)
        assertEquals(listOf<Subject?>(Subject(userId = "u-1", username = "u")), resolved)
    }

    @Test
    fun `shared-leave-09 Unknownからの確定につきonSubjectResolvedは1回で確定の後のloginと再実行では増えない`() = runTest {
        val resolved = mutableListOf<Subject?>()
        val viewModel = newSession(
            apiClient = FakeApiClient(
                onLogin = { _, _ -> LoginResponse(token = "new-token", user = userWithId("u-1")) },
                onMe = { if (resolved.isEmpty()) throw ApiException.Unauthorized() else userWithId("u-1") },
                onFetchPreferences = { preferences },
            ),
            sessionStore = InMemorySessionStore(initialToken = "token-abc"),
            onSubjectResolved = { resolved += it },
        )

        viewModel.refreshAuth() // Unauthorized で Unauthenticated に確定する。

        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
        assertEquals(listOf<Subject?>(null), resolved)

        viewModel.login("u", "p")
        assertTrue(viewModel.authState.value is AuthState.Authenticated)
        assertEquals(1, resolved.size)

        viewModel.refreshAuth()
        assertEquals(1, resolved.size)
    }

    @Test
    fun `shared-leave-09 Unknownを抜ける残りの書込経路はそれぞれ確定につき1回だけ呼ぶ`() = runTest {
        // トークン無しの refreshAuth
        run {
            val resolved = mutableListOf<Subject?>()
            val viewModel = newSession(
                sessionStore = InMemorySessionStore(initialToken = null),
                onSubjectResolved = { resolved += it },
            )
            viewModel.refreshAuth()
            assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
            assertEquals(listOf<Subject?>(null), resolved)
        }
        // onUnauthorized（失効通知）
        run {
            val resolved = mutableListOf<Subject?>()
            val viewModel = newSession(
                sessionStore = InMemorySessionStore(initialToken = "t"),
                onSubjectResolved = { resolved += it },
            )
            viewModel.onUnauthorized("t")
            assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
            assertEquals(listOf<Subject?>(null), resolved)
            viewModel.onUnauthorized("t")
            assertEquals(1, resolved.size)
        }
        // login
        run {
            val resolved = mutableListOf<Subject?>()
            val viewModel = newSession(
                apiClient = FakeApiClient(onLogin = { _, _ -> LoginResponse(token = "tk", user = userWithId("u-2")) }),
                onSubjectResolved = { resolved += it },
            )
            viewModel.login("u", "p")
            assertEquals(listOf<Subject?>(Subject("u-2", "u")), resolved)
        }
        // completePasskeyLogin
        run {
            val resolved = mutableListOf<Subject?>()
            val viewModel = newSession(onSubjectResolved = { resolved += it })
            viewModel.completePasskeyLogin(LoginResponse(token = "tk", user = userWithId("u-3")).toIssuedSession())
            assertEquals(listOf<Subject?>(Subject("u-3", "u")), resolved)
            viewModel.completePasskeyLogin(LoginResponse(token = "tk2", user = userWithId("u-3")).toIssuedSession())
            assertEquals(1, resolved.size)
        }
    }

    @Test
    fun `shared-leave-09 Authenticatedで失効通知を受けてもonSubjectResolvedを呼ばない`() = runTest {
        val resolved = mutableListOf<Subject?>()
        val viewModel = newSession(
            apiClient = FakeApiClient(
                onMe = { userWithId("u-1") },
                onFetchPreferences = { preferences },
            ),
            sessionStore = InMemorySessionStore(initialToken = "t"),
            onSubjectResolved = { resolved += it },
        )
        viewModel.refreshAuth()
        assertEquals(listOf<Subject?>(Subject("u-1", "u")), resolved)

        viewModel.onUnauthorized("t")

        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
        assertEquals(listOf<Subject?>(Subject("u-1", "u")), resolved)
    }

    @Test
    fun `shared-leave-09 Authenticatedの後の再実行がUnauthorizedでもonSubjectResolvedを呼ばない`() = runTest {
        var meUnauthorized = false
        val resolved = mutableListOf<Subject?>()
        val viewModel = newSession(
            apiClient = FakeApiClient(
                onMe = { if (meUnauthorized) throw ApiException.Unauthorized() else userWithId("u-1") },
                onFetchPreferences = { preferences },
            ),
            sessionStore = InMemorySessionStore(initialToken = "t"),
            onSubjectResolved = { resolved += it },
        )
        viewModel.refreshAuth()
        assertEquals(listOf<Subject?>(Subject("u-1", "u")), resolved)

        meUnauthorized = true
        viewModel.refreshAuth()

        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
        assertEquals(listOf<Subject?>(Subject("u-1", "u")), resolved)
    }

    @Test
    fun `shared-leave-09 Unauthenticatedでトークン無しの再実行をしてもonSubjectResolvedは増えない`() = runTest {
        val resolved = mutableListOf<Subject?>()
        val viewModel = newSession(
            sessionStore = InMemorySessionStore(initialToken = null),
            onSubjectResolved = { resolved += it },
        )
        viewModel.refreshAuth()
        assertEquals(listOf<Subject?>(null), resolved)

        viewModel.refreshAuth()

        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
        assertEquals(listOf<Subject?>(null), resolved)
    }

    @Test
    fun `shared-leave-08 me成功のonSubjectResolvedはpreferencesの同期より前に発火する`() = runTest {
        val events = mutableListOf<String>()
        val viewModel = newSession(
            apiClient = FakeApiClient(onMe = { userWithId("u-1") }),
            sessionStore = InMemorySessionStore(initialToken = "t"),
            onSubjectResolved = { events += "resolved" },
            onSessionRestored = { events += "sessionRestored" },
        )

        viewModel.refreshAuth()

        assertEquals(listOf("resolved", "sessionRestored"), events)
    }

    @Test
    fun `shared-leave-09 保存に失敗して確定しないloginではonSubjectResolvedを呼ばない`() = runTest {
        val resolved = mutableListOf<Subject?>()
        val viewModel = newSession(
            apiClient = FakeApiClient(onLogin = { _, _ -> LoginResponse(token = "tk", user = userWithId("u-1")) }),
            sessionStore = InMemorySessionStore(saveFails = true),
            onSubjectResolved = { resolved += it },
        )

        viewModel.login("u", "p")

        assertEquals(AuthState.Unknown, viewModel.authState.value)
        assertEquals(0, resolved.size)
    }

    @Test
    fun `shared-leave-10 user_idが欠落や形式不正でもAuthenticatedになりcurrentSubjectIdはnullでSubjectのuserIdはnullで1回呼ぶ`() = runTest {
        listOf<String?>(null, "", "bad id", "../evil").forEach { userId ->
            val resolved = mutableListOf<Subject?>()
            val viewModel = newSession(
                apiClient = FakeApiClient(onLogin = { _, _ -> LoginResponse(token = "tk", user = userWithId(userId)) }),
                onSubjectResolved = { resolved += it },
            )

            viewModel.login("u", "p")

            assertTrue("userId=$userId", viewModel.authState.value is AuthState.Authenticated)
            assertNull("userId=$userId", viewModel.currentSubjectId())
            assertEquals("userId=$userId", listOf<Subject?>(Subject(userId = null, username = "u")), resolved)
        }
    }

    @Test
    fun `shared-leave-10 currentSubjectIdはAuthenticatedなら正しいuser_idを返しそれ以外はnullを返す`() = runTest {
        val viewModel = newSession(
            apiClient = FakeApiClient(onLogin = { _, _ -> LoginResponse(token = "tk", user = userWithId("u-1_A")) }),
        )
        assertNull(viewModel.currentSubjectId()) // Unknown

        viewModel.login("u", "p")
        assertEquals("u-1_A", viewModel.currentSubjectId())

        viewModel.logout()
        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
        assertNull(viewModel.currentSubjectId())
    }

    // --- 離脱の導出と後始末の手順列（shared-leave-01・02・04・07。SubjectCleanup 実物＋delay の手順） ---

    @Test
    fun `shared-leave-02 android-test-14 Authenticatedで保存トークン付きの失効通知を受けるとlogoutと同じ事後条件になる`() = runTest {
        // verifies: android-contract-14
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        val calls = mutableListOf<Pair<String, Subject>>()
        val cleanup = newCleanup(recordingSteps(calls))
        val sent = mutableListOf<String>()
        val viewModel = authenticatedSession(
            sessionStore = sessionStore,
            onLogout = { sent += it },
            onSubjectLeave = cleanup::start,
        )

        viewModel.onUnauthorized("token-abc")
        advanceUntilIdle()

        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
        assertNull(sessionStore.load())
        assertEquals(cleanupStepNames, calls.map { it.first })
        assertEquals(List(5) { Subject("u-1", "u") }, calls.map { it.second })
        assertTrue("失効通知ではサーバ logout を送らない", sent.isEmpty())
    }

    @Test
    fun `shared-leave-02 android-test-14 Authenticatedの再実行のmeがUnauthorizedでも離脱主体の後始末が起動される`() = runTest {
        // verifies: android-contract-14
        val left = mutableListOf<Subject>()
        var meUnauthorized = false
        val viewModel = newSession(
            apiClient = FakeApiClient(
                onMe = { if (meUnauthorized) throw ApiException.Unauthorized() else userWithId("u-1") },
                onFetchPreferences = { preferences },
            ),
            sessionStore = InMemorySessionStore(initialToken = "token-abc"),
            onSubjectLeave = { left += it },
        )
        viewModel.refreshAuth()
        assertTrue(left.isEmpty())
        meUnauthorized = true

        viewModel.refreshAuth()
        advanceUntilIdle()

        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
        assertEquals(listOf(Subject("u-1", "u")), left)
    }

    @Test
    fun `shared-leave-02 同じ失効通知が2回届いても後始末は1回だけ起動される`() = runTest {
        var leaveCount = 0
        val viewModel = authenticatedSession(onSubjectLeave = { leaveCount++ })

        viewModel.onUnauthorized("token-abc")
        viewModel.onUnauthorized("token-abc")
        advanceUntilIdle()

        assertEquals(1, leaveCount)
    }

    @Test
    fun `shared-leave-04 preferencesの失敗は旧主体の4keyを残し別主体の次のlogoutのpreferences手順で消える`() = runTest {
        // verifies: android-contract-15（入口 ② の次回起動の回収は音声キャッシュだけで、4 key は次の離脱が消す）
        val preferencesStore = InMemoryPreferencesStore(
            initialDefaultDifficulty = Difficulty.TOEIC_900,
            initialDefaultPlaybackSpeed = PlaybackSpeed.of(2.0)!!,
            initialWeeklyGoalEpisodes = WeeklyGoal.of(10)!!,
            initialSeenAchievementIds = setOf("streak_7"),
        )
        var preferencesAttempts = 0
        val cleanup = newCleanup(
            listOf(
                CleanupStep("preferences") {
                    if (preferencesAttempts++ == 0) throw RuntimeException("clear failed")
                    preferencesStore.clearSubjectScoped()
                },
            ),
        )
        val loginResponse = LoginResponse(token = "token-b", user = userWithId("u-2", "other"))
        val viewModel = authenticatedSession(
            userId = "u-1",
            onLogin = { _, _ -> loginResponse },
            onSubjectLeave = cleanup::start,
        )
        // 失敗した後始末が旧主体の 4 key を残すことを比べるため、離脱の前にスナップショットを取る。
        val difficulty = preferencesStore.defaultDifficulty.value
        val speed = preferencesStore.defaultPlaybackSpeed.value
        val goal = preferencesStore.weeklyGoalEpisodes.value
        val seen = preferencesStore.seenAchievementIds.value

        viewModel.logout()
        advanceUntilIdle()

        assertEquals(CleanupIncomplete(setOf("preferences")), cleanup.cleanupIncomplete.value)
        assertEquals(difficulty, preferencesStore.defaultDifficulty.value)
        assertEquals(speed, preferencesStore.defaultPlaybackSpeed.value)
        assertEquals(goal, preferencesStore.weeklyGoalEpisodes.value)
        assertEquals(seen, preferencesStore.seenAchievementIds.value)

        viewModel.login("other", "p")
        viewModel.logout()
        advanceUntilIdle()

        assertNull(cleanup.cleanupIncomplete.value)
        assertEquals(Difficulty.TOEIC_600, preferencesStore.defaultDifficulty.value)
        assertEquals(PlaybackSpeed.DEFAULT, preferencesStore.defaultPlaybackSpeed.value)
        assertEquals(WeeklyGoal.DEFAULT, preferencesStore.weeklyGoalEpisodes.value)
        assertEquals(emptySet<String>(), preferencesStore.seenAchievementIds.value)
    }

    @Test
    fun `shared-leave-07 android-test-14 Authenticated(A)からBのloginでAの後始末が起動されBの確立は待たない`() = runTest {
        // verifies: android-contract-14
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        val calls = mutableListOf<Pair<String, Subject>>()
        val cleanup = newCleanup(recordingSteps(calls, pauseMillis = 1_000))
        val userB = userWithId("u-2", "bob")
        val viewModel = authenticatedSession(
            userId = "u-1",
            sessionStore = sessionStore,
            onLogin = { _, _ -> LoginResponse(token = "token-b", user = userB) },
            onSubjectLeave = cleanup::start,
        )

        launch { viewModel.login("bob", "p") }
        runCurrent()

        assertEquals(AuthState.Authenticated(userB.toAccountUser()), viewModel.authState.value)
        assertEquals("token-b", sessionStore.load())
        assertTrue("A の手順はまだ完了していない", calls.size < cleanupStepNames.size)

        advanceUntilIdle()

        assertEquals(List(5) { Subject("u-1", "u") }, calls.map { it.second })
    }

    @Test
    fun `shared-leave-07 遷移④でonSubjectResolvedがBで1回呼ばれshared-leave-09の回数に入らない`() = runTest {
        val events = mutableListOf<String>()
        val resolved = mutableListOf<Subject?>()
        val userB = userWithId("u-2", "bob")
        val viewModel = authenticatedSession(
            userId = "u-1",
            onLogin = { _, _ -> LoginResponse(token = "token-b", user = userB) },
            onSubjectLeave = { events += "leave:${it.username}" },
            onSubjectResolved = { resolved += it; events += "resolved:${it?.username}" },
            onAuthenticated = { events += "authenticated" },
        )
        assertEquals(listOf<Subject?>(Subject("u-1", "u")), resolved)
        events.clear()

        viewModel.login("bob", "p")
        advanceUntilIdle()

        assertEquals(listOf<Subject?>(Subject("u-1", "u"), Subject("u-2", "bob")), resolved)
        assertEquals(listOf("leave:u", "resolved:bob", "authenticated"), events)
    }

    @Test
    fun `shared-leave-07 同じ主体のloginとapplyProfileUpdateとmeの再取得は離脱を起動しない`() = runTest {
        var leaveCount = 0
        val viewModel = authenticatedSession(
            userId = "u-1",
            onLogin = { _, _ -> LoginResponse(token = "token-2", user = userWithId("u-1")) },
            onSubjectLeave = { leaveCount++ },
        )

        viewModel.login("u", "p")
        viewModel.applyProfileUpdate("Renamed")
        viewModel.refreshAuth()
        advanceUntilIdle()

        assertEquals(0, leaveCount)
        assertTrue(viewModel.authState.value is AuthState.Authenticated)
    }

    @Test
    fun `shared-leave-07 completePasskeyLoginでも遷移④はAの後始末を起動する`() = runTest {
        val left = mutableListOf<Subject>()
        val viewModel = authenticatedSession(userId = "u-1", onSubjectLeave = { left += it })

        viewModel.completePasskeyLogin(LoginResponse(token = "token-b", user = userWithId("u-2", "bob")).toIssuedSession())
        advanceUntilIdle()

        assertEquals(listOf(Subject("u-1", "u")), left)
    }

    @Test
    fun `shared-leave-07 userIdが片方でも欠落なら同一主体はusernameで判定し両方あればuserIdで判定する`() = runTest {
        // (subject A: userId, username) -> (subject B: userId, username) => 離脱するか
        data class Case(val a: Pair<String?, String>, val b: Pair<String?, String>, val leaves: Boolean)
        val cases = listOf(
            Case(null to "x", "u-1" to "x", leaves = false),
            Case("u-1" to "x", null to "x", leaves = false),
            Case(null to "x", "u-1" to "y", leaves = true),
            Case("u-1" to "x", "u-1" to "y", leaves = false),
            Case("u-1" to "x", "u-2" to "x", leaves = true),
        )
        cases.forEach { c ->
            val left = mutableListOf<Subject>()
            val viewModel = authenticatedSession(
                userId = c.a.first,
                username = c.a.second,
                onLogin = { _, _ ->
                    LoginResponse(token = "token-b", user = UserResponse(c.b.second, "member", "U", c.b.first))
                },
                onSubjectLeave = { left += it },
            )
            viewModel.login(c.b.second, "p")
            advanceUntilIdle()
            assertEquals("$c", if (c.leaves) 1 else 0, left.size)
        }
    }

    @Test
    fun `logoutはUnknownからでも後始末を起動せずonSubjectResolvedをnullで1回呼ぶ`() = runTest {
        val resolved = mutableListOf<Subject?>()
        val sent = mutableListOf<String>()
        var leaveCount = 0
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        val viewModel = newSession(
            apiClient = FakeApiClient(onLogout = { sent += it }),
            sessionStore = sessionStore,
            onSubjectLeave = { leaveCount++ },
            onSubjectResolved = { resolved += it },
        )

        viewModel.logout()
        advanceUntilIdle()

        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
        assertEquals(0, leaveCount)
        assertEquals(listOf<Subject?>(null), resolved)
        assertNull(sessionStore.load())
        assertEquals(listOf("token-abc"), sent)
    }

    @Test
    fun `logoutでトークンが無ければapiClientのlogoutを呼ばない`() = runTest {
        val sent = mutableListOf<String>()
        val viewModel = newSession(
            apiClient = FakeApiClient(onLogout = { sent += it }),
            sessionStore = InMemorySessionStore(initialToken = null),
        )

        viewModel.logout()
        advanceUntilIdle()

        assertTrue(sent.isEmpty())
    }

    @Test
    fun `logoutはUnauthenticatedからでは後始末もonSubjectResolvedも呼ばない`() = runTest {
        val resolved = mutableListOf<Subject?>()
        val sent = mutableListOf<String>()
        var leaveCount = 0
        val viewModel = newSession(
            apiClient = FakeApiClient(onLogout = { sent += it }),
            sessionStore = InMemorySessionStore(initialToken = null),
            onSubjectLeave = { leaveCount++ },
            onSubjectResolved = { resolved += it },
        )
        viewModel.refreshAuth()
        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
        val resolvedBefore = resolved.size

        viewModel.logout()
        advanceUntilIdle()

        assertEquals(0, leaveCount)
        assertEquals(resolvedBefore, resolved.size)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `onSubjectLeaveとonSubjectResolvedはsessionLockの外で呼ばれる`() = runTest {
        // 両 hook の中から別スレッドで onUnauthorized を呼ぶ。lock を保持したまま呼ばれていれば、別スレッドは待たされて終了しない
        // （同じスレッドからの呼出は synchronized が再入可能で検出できない）。
        val blocked = mutableListOf<String>()
        lateinit var viewModel: AuthSession
        fun probeFromOtherThread(label: String) {
            val thread = Thread { viewModel.onUnauthorized("x") }
            thread.start()
            thread.join(5_000)
            if (thread.isAlive) blocked += label
        }
        viewModel = authenticatedSession(
            userId = "u-1",
            onLogin = { _, _ -> LoginResponse(token = "token-b", user = userWithId("u-2", "bob")) },
            onSubjectLeave = { probeFromOtherThread("leave") },
            onSubjectResolved = { if (it?.userId == "u-2") probeFromOtherThread("resolved") },
        )

        viewModel.login("bob", "p")
        viewModel.logout()
        advanceUntilIdle()

        assertTrue("lock を保持したまま hook を呼んでいる: $blocked", blocked.isEmpty())
    }

    // --- LoginOutcome（android-dec-84: login と passkey の確立の結果は例外でなく値で返す） ---

    @Test
    fun `android-dec-84 loginが成功するとEstablishedを返しauthStateがAuthenticatedになる`() = runTest {
        val sessionStore = InMemorySessionStore(initialToken = null)
        val session = newSession(
            apiClient = FakeApiClient(onLogin = { _, _ -> LoginResponse(token = "new-token", user = user) }),
            sessionStore = sessionStore,
        )

        val outcome = session.login("u", "p")

        assertEquals(LoginOutcome.Established, outcome)
        assertEquals(AuthState.Authenticated(user.toAccountUser()), session.authState.value)
        assertEquals("new-token", sessionStore.load())
    }

    @Test
    fun `android-dec-84 loginがUnauthorizedを受けるとInvalidCredentialsを返し状態を変えない`() = runTest {
        val sessionStore = InMemorySessionStore(initialToken = null)
        var authenticatedCalled = false
        val session = newSession(
            apiClient = FakeApiClient(onLogin = { _, _ -> throw ApiException.Unauthorized() }),
            sessionStore = sessionStore,
            onAuthenticated = { authenticatedCalled = true },
        )

        val outcome = session.login("u", "wrong-password")

        assertEquals(LoginOutcome.InvalidCredentials, outcome)
        assertEquals(AuthState.Unknown, session.authState.value)
        assertNull(sessionStore.load())
        assertFalse(authenticatedCalled)
    }

    @Test
    fun `android-dec-84 loginがRateLimitedやNetworkErrorを受けるとFailedに原因を載せて返す`() = runTest {
        listOf<ApiException>(
            ApiException.RateLimited(retryAfterSeconds = 30),
            ApiException.NetworkError(RuntimeException("offline")),
        ).forEach { failure ->
            val session = newSession(apiClient = FakeApiClient(onLogin = { _, _ -> throw failure }))

            val outcome = session.login("u", "p")

            assertTrue("$failure", outcome is LoginOutcome.Failed)
            assertSame("$failure", failure, (outcome as LoginOutcome.Failed).cause)
        }
    }

    @Test
    fun `android-dec-84 保存に失敗したloginはSaveFailedを返しAuthenticatedへ遷移しない`() = runTest {
        var authenticatedCalled = false
        val session = newSession(
            apiClient = FakeApiClient(onLogin = { _, _ -> LoginResponse(token = "new-token", user = user) }),
            sessionStore = InMemorySessionStore(saveFails = true),
            onAuthenticated = { authenticatedCalled = true },
        )

        val outcome = session.login("u", "p")

        assertEquals(LoginOutcome.SaveFailed, outcome)
        assertEquals(AuthState.Unknown, session.authState.value)
        assertFalse(authenticatedCalled)
    }

    @Test
    fun `android-dec-84 loginの失敗は先に立っていたlastFailureを消さずに残す`() = runTest {
        val earlier = ApiException.NetworkError(RuntimeException("offline"))
        val cases: List<Triple<ApiException?, Boolean, (LoginOutcome) -> Boolean>> = listOf(
            Triple(ApiException.Unauthorized(), false, { o: LoginOutcome -> o == LoginOutcome.InvalidCredentials }),
            Triple(ApiException.RateLimited(retryAfterSeconds = 30), false, { o: LoginOutcome -> o is LoginOutcome.Failed }),
            Triple(null, true, { o: LoginOutcome -> o == LoginOutcome.SaveFailed }),
        )
        cases.forEach { (loginFailure, saveFails, isExpected) ->
            val session = newSession(
                apiClient = FakeApiClient(
                    onMe = { throw earlier },
                    onLogin = { _, _ ->
                        loginFailure?.let { throw it }
                        LoginResponse(token = "new-token", user = user)
                    },
                ),
                sessionStore = InMemorySessionStore(initialToken = "token-abc", saveFails = saveFails),
            )
            session.refreshAuth()
            assertSame("$loginFailure", earlier, session.lastFailure.value)
            assertEquals(AuthState.Unknown, session.authState.value)

            val outcome = session.login("u", "p")

            assertTrue("$loginFailure: $outcome", isExpected(outcome))
            assertSame("$loginFailure", earlier, session.lastFailure.value)
            assertEquals("$loginFailure", AuthState.Unknown, session.authState.value)
        }
    }

    @Test
    fun `android-dec-84 確立の後にonAuthenticatedが投げたloginはAuthenticatedのまま例外の型で結果を返す`() = runTest {
        val networkError = ApiException.NetworkError(RuntimeException("offline"))
        listOf<ApiException>(networkError, ApiException.Unauthorized()).forEach { failure ->
            val sessionStore = InMemorySessionStore(initialToken = null)
            val session = newSession(
                apiClient = FakeApiClient(onLogin = { _, _ -> LoginResponse(token = "new-token", user = user) }),
                sessionStore = sessionStore,
                onAuthenticated = { throw failure },
            )

            val outcome = session.login("u", "p")

            if (failure === networkError) {
                assertTrue("$outcome", outcome is LoginOutcome.Failed)
                assertSame(failure, (outcome as LoginOutcome.Failed).cause)
            } else {
                assertEquals(LoginOutcome.InvalidCredentials, outcome)
            }
            assertEquals("$failure", "new-token", sessionStore.load())
            assertEquals("$failure", AuthState.Authenticated(user.toAccountUser()), session.authState.value)
            assertNull("$failure", session.lastFailure.value)
        }
    }

    @Test
    fun `android-dec-84 completePasskeyLoginは保存の成否でEstablishedかSaveFailedを返す`() = runTest {
        val issued = LoginResponse(token = "passkey-token", user = user).toIssuedSession()
        val saved = newSession(sessionStore = InMemorySessionStore(initialToken = null))
        val failed = newSession(sessionStore = InMemorySessionStore(saveFails = true))

        assertEquals(LoginOutcome.Established, saved.completePasskeyLogin(issued))
        assertEquals(LoginOutcome.SaveFailed, failed.completePasskeyLogin(issued))

        assertEquals(AuthState.Authenticated(user.toAccountUser()), saved.authState.value)
        assertEquals(AuthState.Unknown, failed.authState.value)
    }

    // --- applyProfileUpdate（android-dec-86: 表示名だけを替える） ---

    @Test
    fun `android-dec-86 applyProfileUpdateは表示名だけを替えroleとsubjectIdとusernameを保つ`() = runTest {
        val leaves = mutableListOf<Subject>()
        val session = newSession(
            apiClient = FakeApiClient(onMe = { UserResponse(username = "u", role = "admin", displayName = "Old", userId = "u-1") }),
            sessionStore = InMemorySessionStore(initialToken = "token-abc"),
            onSubjectLeave = { leaves += it },
        )
        session.refreshAuth()
        val before = (session.authState.value as AuthState.Authenticated).user

        session.applyProfileUpdate("New")

        val after = (session.authState.value as AuthState.Authenticated).user
        assertEquals(before.copy(displayName = "New"), after)
        assertEquals(AccountUser("u", "New", Role.Admin, SubjectId.of("u-1")), after)
        assertTrue(leaves.isEmpty())
    }

    @Test
    fun `android-dec-86 applyProfileUpdateはAuthenticated以外では状態を変えない`() = runTest {
        val unknown = newSession(sessionStore = InMemorySessionStore(initialToken = null))
        val unauthenticated = newSession(sessionStore = InMemorySessionStore(initialToken = null))
        unauthenticated.refreshAuth()

        unknown.applyProfileUpdate("New")
        unauthenticated.applyProfileUpdate("New")

        assertEquals(AuthState.Unknown, unknown.authState.value)
        assertEquals(AuthState.Unauthenticated, unauthenticated.authState.value)
    }
}
