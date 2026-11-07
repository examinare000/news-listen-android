package com.rioikeda.newslisten.auth

import com.rioikeda.newslisten.model.LoginResponse
import com.rioikeda.newslisten.model.PreferencesResponse
import com.rioikeda.newslisten.model.UserResponse
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.ApiException
import com.rioikeda.newslisten.network.InMemorySessionStore
import com.rioikeda.newslisten.network.SessionStore
import com.rioikeda.newslisten.preferences.InMemoryPreferencesStore
import com.rioikeda.newslisten.preferences.PreferencesStore
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
 * [AuthViewModel] の挙動検証。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/AppState.swift:130-178（refreshAuth/refreshPreferences/logout）、
 * ios/NewsListenApp/NewsListenApp/Auth/LoginViewModel.swift（ログインのエラー文言）のミラー。
 *
 * フェーズ10 P10 Task3: defaultDifficulty/defaultPlaybackSpeed の値の正本は [PreferencesStore] に
 * 一本化した（AuthViewModel は StateFlow を委譲するのみで独自コピーを持たない）。
 *
 * android-slice-auth-01（認証失効の型分離と検知）: `onLogoutCleanup` を `onSubjectLeave` へ改名（SG-R18）。
 * 401 は [ApiException.Unauthorized] 型で受け、一時障害とは区別する（android-contract-12）。
 */
class AuthViewModelTest {

    private val user = UserResponse(username = "u", role = "member", displayName = "U")
    private val preferences = PreferencesResponse(
        defaultDifficulty = "toeic_800",
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
        preferencesStore: PreferencesStore = InMemoryPreferencesStore(),
        onSubjectLeave: (Subject) -> Unit = {},
        onAuthenticated: () -> Unit = {},
        onSubjectResolved: (Subject?) -> Unit = {},
    ): AuthViewModel =
        AuthViewModel(
            apiClient = apiClient,
            sessionStore = sessionStore,
            dispatcher = StandardTestDispatcher(testScheduler),
            preferencesStore = preferencesStore,
            onSubjectLeave = onSubjectLeave,
            onAuthenticated = onAuthenticated,
            onSubjectResolved = onSubjectResolved,
        )

    private fun userWithId(userId: String?, username: String = "u") =
        UserResponse(username = username, role = "member", displayName = "U", userId = userId)

    /** 起点を `Authenticated` にする。`refreshAuth()` を先に呼ぶので、`onFetchPreferences` は stub する。 */
    private suspend fun TestScope.authenticatedViewModel(
        userId: String? = "u-1",
        username: String = "u",
        sessionStore: SessionStore = InMemorySessionStore(initialToken = "token-abc"),
        onLogin: suspend (String, String) -> LoginResponse = { _, _ -> error("login is not stubbed") },
        onLogout: suspend (String) -> Unit = {},
        preferencesStore: PreferencesStore = InMemoryPreferencesStore(),
        onSubjectLeave: (Subject) -> Unit = {},
        onAuthenticated: () -> Unit = {},
        onSubjectResolved: (Subject?) -> Unit = {},
    ): AuthViewModel {
        val viewModel = newViewModel(
            apiClient = FakeApiClient(
                onLogin = onLogin,
                onLogout = onLogout,
                onMe = { userWithId(userId, username) },
                onFetchPreferences = { preferences },
            ),
            sessionStore = sessionStore,
            preferencesStore = preferencesStore,
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
        val viewModel = newViewModel(sessionStore = InMemorySessionStore(initialToken = null))

        viewModel.refreshAuth()

        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
    }

    @Test
    fun トークンありでme成功ならAuthenticatedになりuserを保持する() = runTest {
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onMe = { user }, onFetchPreferences = { preferences }),
            sessionStore = InMemorySessionStore(initialToken = "token-abc"),
        )

        viewModel.refreshAuth()

        assertEquals(AuthState.Authenticated(user), viewModel.authState.value)
    }

    @Test
    fun unauthorized_トークンありでmeがUnauthorizedならトークン破棄してUnauthenticatedになる() = runTest {
        // verifies: android-contract-12
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        var subjectLeaveCalled = false
        val viewModel = newViewModel(
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
        val viewModel = newViewModel(
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
        val viewModel = newViewModel(
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
        val viewModel = newViewModel(
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
        val viewModel = newViewModel(
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
        assertEquals(AuthState.Authenticated(user), viewModel.authState.value)
        assertNull(viewModel.lastFailure.value)
    }

    @Test
    fun rotation_Authenticated中に再実行したrefreshが一時障害でも状態を変えない() = runTest {
        // verifies: android-contract-12（F-2: 画面回転での LaunchedEffect 再実行）
        var callCount = 0
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        val viewModel = newViewModel(
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

        assertEquals(AuthState.Authenticated(user), viewModel.authState.value)
        assertEquals("token-abc", sessionStore.load())
        assertNull(viewModel.lastFailure.value)
    }

    // --- preferences 同期（refreshAuth の Authenticated 後続処理） ---

    @Test
    fun 認証成功後preferences取得成功でStateFlowへ反映される() = runTest {
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onMe = { user }, onFetchPreferences = { preferences }),
            sessionStore = InMemorySessionStore(initialToken = "token-abc"),
        )

        viewModel.refreshAuth()

        assertEquals("toeic_800", viewModel.defaultDifficulty.value)
        assertEquals(1.5, viewModel.defaultPlaybackSpeed.value, 0.0)
        assertFalse(viewModel.preferencesSyncFailed.value)
    }

    @Test
    fun 認証成功後preferences取得成功で注入したPreferencesStore自体が更新される() = runTest {
        // 二重管理でないことの検証: AuthViewModel が内部に独自コピーを持たず、注入した
        // PreferencesStore インスタンスそのものへ書き戻していることを確認する。
        val preferencesStore = InMemoryPreferencesStore()
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onMe = { user }, onFetchPreferences = { preferences }),
            sessionStore = InMemorySessionStore(initialToken = "token-abc"),
            preferencesStore = preferencesStore,
        )

        viewModel.refreshAuth()

        assertEquals("toeic_800", preferencesStore.defaultDifficulty.value)
        assertEquals(1.5, preferencesStore.defaultPlaybackSpeed.value, 0.0)
    }

    @Test
    fun 認証成功後preferences取得失敗でも既存値を保持しフラグを立てる() = runTest {
        val viewModel = newViewModel(
            apiClient = FakeApiClient(
                onMe = { user },
                onFetchPreferences = { throw ApiException.NetworkError(RuntimeException("offline")) },
            ),
            sessionStore = InMemorySessionStore(initialToken = "token-abc"),
        )
        val difficultyBeforeSync = viewModel.defaultDifficulty.value
        val speedBeforeSync = viewModel.defaultPlaybackSpeed.value

        viewModel.refreshAuth()

        assertEquals(difficultyBeforeSync, viewModel.defaultDifficulty.value)
        assertEquals(speedBeforeSync, viewModel.defaultPlaybackSpeed.value, 0.0)
        assertTrue(viewModel.preferencesSyncFailed.value)
    }

    // --- login（android-contract-23, android-test-25） ---

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
        assertEquals(AuthState.Authenticated(user), viewModel.authState.value)
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

    // --- login / completePasskeyLogin の保存失敗（android-contract-16, CI-S0-3） ---

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

        viewModel.completePasskeyLogin(loginResponse)

        assertEquals(AuthState.Unknown, viewModel.authState.value)
        assertEquals(sessionSaveErrorMessage, viewModel.loginErrorMessage.value)
        assertFalse(authenticatedCalled)
    }

    // --- logout ---

    @Test
    fun `logoutはAPI失敗でもトークンを破棄しUnauthenticatedになる`() = runTest {
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        val viewModel = newViewModel(
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
        val viewModel = authenticatedViewModel(userId = "u-1", onSubjectLeave = { left += it })

        viewModel.logout()
        advanceUntilIdle()

        assertEquals(listOf(Subject("u-1", "u")), left)
    }

    @Test
    fun `logoutはonSubjectLeaveが同期で例外を投げてもトークンを破棄しUnauthenticatedになりapiClientのlogoutを送る`() = runTest {
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        val sent = mutableListOf<String>()
        val viewModel = authenticatedViewModel(
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
        val viewModel = newViewModel(
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
        val viewModel = newViewModel(
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
        val viewModel = newViewModel(
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
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onLogin = { _, _ -> loginResponse }),
            onAuthenticated = { authenticatedCalled = true },
        )

        viewModel.login("u", "p")

        assertTrue(authenticatedCalled)
    }

    @Test
    fun login失敗ならonAuthenticatedは呼ばれない() = runTest {
        var authenticatedCalled = false
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onLogin = { _, _ -> throw ApiException.Unauthorized() }),
            onAuthenticated = { authenticatedCalled = true },
        )

        viewModel.login("u", "wrong-password")

        assertFalse(authenticatedCalled)
    }

    @Test
    fun logoutではonAuthenticatedは呼ばれない() = runTest {
        var authenticatedCalled = false
        val viewModel = newViewModel(onAuthenticated = { authenticatedCalled = true })

        viewModel.logout()

        assertFalse(authenticatedCalled)
    }

    // --- completePasskeyLogin（フェーズ17 P17: パスキーログイン成功時のセッション確立） ---

    @Test
    fun completePasskeyLoginでトークン保存しAuthenticatedになる() = runTest {
        val sessionStore = InMemorySessionStore(initialToken = null)
        val loginResponse = LoginResponse(token = "passkey-token", user = user)
        val viewModel = newViewModel(sessionStore = sessionStore)

        viewModel.completePasskeyLogin(loginResponse)

        assertEquals("passkey-token", sessionStore.load())
        assertEquals(AuthState.Authenticated(user), viewModel.authState.value)
        assertNull(viewModel.loginErrorMessage.value)
    }

    @Test
    fun completePasskeyLoginでonAuthenticatedが呼ばれる() = runTest {
        var authenticatedCalled = false
        val loginResponse = LoginResponse(token = "passkey-token", user = user)
        val viewModel = newViewModel(onAuthenticated = { authenticatedCalled = true })

        viewModel.completePasskeyLogin(loginResponse)

        assertTrue(authenticatedCalled)
    }

    // --- applyProfileUpdate（フェーズ11 P11 T2: 表示名更新のAuthState反映） ---

    @Test
    fun applyProfileUpdateはAuthenticated状態でauthStateのuserを更新する() = runTest {
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onMe = { user }, onFetchPreferences = { preferences }),
            sessionStore = InMemorySessionStore(initialToken = "token-abc"),
        )
        viewModel.refreshAuth()
        val updatedUser = user.copy(displayName = "新しい表示名")

        viewModel.applyProfileUpdate(updatedUser)

        assertEquals(AuthState.Authenticated(updatedUser), viewModel.authState.value)
    }

    @Test
    fun applyProfileUpdateは非Authenticatedならno_opになる() = runTest {
        val viewModel = newViewModel(sessionStore = InMemorySessionStore(initialToken = null))
        viewModel.refreshAuth()
        val updatedUser = user.copy(displayName = "新しい表示名")

        viewModel.applyProfileUpdate(updatedUser)

        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
    }

    // --- onUnauthorized とセッション書込みの排他（CI-S0-5, T-S0-5） ---

    @Test
    fun revocation_付与したトークンが現在値ならUnauthenticatedにしてトークンを破棄する() = runTest {
        // verifies: CI-S0-5
        val sessionStore = InMemorySessionStore(initialToken = "t")
        var subjectLeaveCalled = false
        val viewModel = newViewModel(
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
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onLogin = { _, _ -> loginResponse }),
            sessionStore = sessionStore,
        )
        viewModel.login("u", "p")

        viewModel.onUnauthorized("t1")

        assertEquals(AuthState.Authenticated(user), viewModel.authState.value)
        assertEquals("t2", sessionStore.load())
    }

    @Test
    fun refresh_superseded_success_me成功の途中で失効するとAuthenticatedを復活させない() = runTest {
        // verifies: CI-S0-5
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        var authenticatedCalled = false
        lateinit var viewModel: AuthViewModel
        viewModel = newViewModel(
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
        val viewModel = newViewModel(
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
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onMe = { user }, onFetchPreferences = { preferences }),
            sessionStore = sessionStore,
        )
        viewModel.refreshAuth()
        val updatedUser = user.copy(displayName = "新しい表示名")

        viewModel.onUnauthorized("token-abc")
        viewModel.applyProfileUpdate(updatedUser)

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
        lateinit var viewModel: AuthViewModel
        viewModel = authenticatedViewModel(
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
        lateinit var viewModel: AuthViewModel
        viewModel = authenticatedViewModel(
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
        lateinit var viewModel: AuthViewModel
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
        viewModel = authenticatedViewModel(
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
        lateinit var viewModel: AuthViewModel
        viewModel = authenticatedViewModel(
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
        val viewModel = authenticatedViewModel(
            sessionStore = sessionStore,
            onLogin = { _, _ -> loginResponse },
        )
        viewModel.logout()
        advanceUntilIdle()
        viewModel.login("u", "p")
        assertEquals(AuthState.Authenticated(userWithId("u-1")), viewModel.authState.value)

        viewModel.onUnauthorized("token-2")

        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
        assertNull(sessionStore.load())
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

    // --- retryRefreshAuth（CI-S0-8, T-S0-8） ---

    @Test
    fun completes_without_caller_scope_呼出元のscopeが無くてもrefreshが完走する() = runTest {
        // verifies: CI-S0-8
        var authenticatedCallCount = 0
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onMe = { user }, onFetchPreferences = { preferences }),
            sessionStore = InMemorySessionStore(initialToken = "token-abc"),
            onAuthenticated = { authenticatedCallCount++ },
        )

        viewModel.retryRefreshAuth()
        advanceUntilIdle()

        assertEquals(AuthState.Authenticated(user), viewModel.authState.value)
        assertNull(viewModel.lastFailure.value)
        assertEquals("toeic_800", viewModel.defaultDifficulty.value)
        assertEquals(1, authenticatedCallCount)
    }

    @Test
    fun dedup_while_running_実行中の再呼出は無視され1回だけ実行される() = runTest {
        // verifies: CI-S0-8
        var meCallCount = 0
        var authenticatedCallCount = 0
        val viewModel = newViewModel(
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
        val viewModel = newViewModel(
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
        assertEquals(AuthState.Authenticated(user), viewModel.authState.value)
        assertNull(viewModel.lastFailure.value)
        assertEquals(2, callCount)
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

        assertEquals(AuthState.Authenticated(user), viewModel.authState.value)
        assertNull(viewModel.lastFailure.value)
    }

    // --- 起動時回収の発火（onSubjectResolved。shared-leave-08・09・10 / android-dec-48） ---

    @Test
    fun `shared-leave-08 Unknownの間はonSubjectResolvedを呼ばず再試行でmeが成功すると1回呼ぶ`() = runTest {
        var meFails = true
        val resolved = mutableListOf<Subject?>()
        val viewModel = newViewModel(
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
        val viewModel = newViewModel(
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
            val viewModel = newViewModel(
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
            val viewModel = newViewModel(
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
            val viewModel = newViewModel(
                apiClient = FakeApiClient(onLogin = { _, _ -> LoginResponse(token = "tk", user = userWithId("u-2")) }),
                onSubjectResolved = { resolved += it },
            )
            viewModel.login("u", "p")
            assertEquals(listOf<Subject?>(Subject("u-2", "u")), resolved)
        }
        // completePasskeyLogin
        run {
            val resolved = mutableListOf<Subject?>()
            val viewModel = newViewModel(onSubjectResolved = { resolved += it })
            viewModel.completePasskeyLogin(LoginResponse(token = "tk", user = userWithId("u-3")))
            assertEquals(listOf<Subject?>(Subject("u-3", "u")), resolved)
            viewModel.completePasskeyLogin(LoginResponse(token = "tk2", user = userWithId("u-3")))
            assertEquals(1, resolved.size)
        }
    }

    @Test
    fun `shared-leave-09 Authenticatedで失効通知を受けてもonSubjectResolvedを呼ばない`() = runTest {
        val resolved = mutableListOf<Subject?>()
        val viewModel = newViewModel(
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
        val viewModel = newViewModel(
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
        val viewModel = newViewModel(
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
        val viewModel = newViewModel(
            apiClient = FakeApiClient(
                onMe = { userWithId("u-1") },
                onFetchPreferences = {
                    events += "fetchPreferences"
                    preferences
                },
            ),
            sessionStore = InMemorySessionStore(initialToken = "t"),
            onSubjectResolved = { events += "resolved" },
        )

        viewModel.refreshAuth()

        assertEquals(listOf("resolved", "fetchPreferences"), events)
    }

    @Test
    fun `shared-leave-09 保存に失敗して確定しないloginではonSubjectResolvedを呼ばない`() = runTest {
        val resolved = mutableListOf<Subject?>()
        val viewModel = newViewModel(
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
            val viewModel = newViewModel(
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
        val viewModel = newViewModel(
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
    fun `shared-leave-02 android-test-14 Authenticatedで保存トークン付きの失効通知を受けるとlogoutと同じ事後条件になる`() = runTest {
        // verifies: android-contract-14
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        val calls = mutableListOf<Pair<String, Subject>>()
        val cleanup = newCleanup(recordingSteps(calls))
        val sent = mutableListOf<String>()
        val viewModel = authenticatedViewModel(
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
        val viewModel = newViewModel(
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
        val viewModel = authenticatedViewModel(onSubjectLeave = { leaveCount++ })

        viewModel.onUnauthorized("token-abc")
        viewModel.onUnauthorized("token-abc")
        advanceUntilIdle()

        assertEquals(1, leaveCount)
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
    fun `shared-leave-04 preferencesの失敗は旧主体の4keyを残し別主体の次のlogoutのpreferences手順で消える`() = runTest {
        // verifies: android-contract-15（入口 ② の次回起動の回収は音声キャッシュだけで、4 key は次の離脱が消す）
        val preferencesStore = InMemoryPreferencesStore(
            initialDefaultDifficulty = "toeic_900",
            initialDefaultPlaybackSpeed = 2.0,
            initialWeeklyGoalEpisodes = 9,
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
        val viewModel = authenticatedViewModel(
            userId = "u-1",
            preferencesStore = preferencesStore,
            onLogin = { _, _ -> loginResponse },
            onSubjectLeave = cleanup::start,
        )
        // refreshAuth の同期が 3 項目を stub で上書きするので、比較のスナップショットは refreshAuth の後に取る。
        val difficulty = preferencesStore.defaultDifficulty.value
        val speed = preferencesStore.defaultPlaybackSpeed.value
        val goal = preferencesStore.weeklyGoalEpisodes.value
        val seen = preferencesStore.seenAchievementIds.value

        viewModel.logout()
        advanceUntilIdle()

        assertEquals(CleanupIncomplete(setOf("preferences")), cleanup.cleanupIncomplete.value)
        assertEquals(difficulty, preferencesStore.defaultDifficulty.value)
        assertEquals(speed, preferencesStore.defaultPlaybackSpeed.value, 0.0)
        assertEquals(goal, preferencesStore.weeklyGoalEpisodes.value)
        assertEquals(seen, preferencesStore.seenAchievementIds.value)

        viewModel.login("other", "p")
        viewModel.logout()
        advanceUntilIdle()

        assertNull(cleanup.cleanupIncomplete.value)
        assertEquals("toeic_600", preferencesStore.defaultDifficulty.value)
        assertEquals(1.0, preferencesStore.defaultPlaybackSpeed.value, 0.0)
        assertEquals(3, preferencesStore.weeklyGoalEpisodes.value)
        assertEquals(emptySet<String>(), preferencesStore.seenAchievementIds.value)
    }

    @Test
    fun `shared-leave-07 android-test-14 Authenticated(A)からBのloginでAの後始末が起動されBの確立は待たない`() = runTest {
        // verifies: android-contract-14
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        val calls = mutableListOf<Pair<String, Subject>>()
        val cleanup = newCleanup(recordingSteps(calls, pauseMillis = 1_000))
        val userB = userWithId("u-2", "bob")
        val viewModel = authenticatedViewModel(
            userId = "u-1",
            sessionStore = sessionStore,
            onLogin = { _, _ -> LoginResponse(token = "token-b", user = userB) },
            onSubjectLeave = cleanup::start,
        )

        launch { viewModel.login("bob", "p") }
        runCurrent()

        assertEquals(AuthState.Authenticated(userB), viewModel.authState.value)
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
        val viewModel = authenticatedViewModel(
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
        val viewModel = authenticatedViewModel(
            userId = "u-1",
            onLogin = { _, _ -> LoginResponse(token = "token-2", user = userWithId("u-1")) },
            onSubjectLeave = { leaveCount++ },
        )

        viewModel.login("u", "p")
        viewModel.applyProfileUpdate(userWithId("u-1").copy(displayName = "Renamed"))
        viewModel.refreshAuth()
        advanceUntilIdle()

        assertEquals(0, leaveCount)
        assertTrue(viewModel.authState.value is AuthState.Authenticated)
    }

    @Test
    fun `shared-leave-07 completePasskeyLoginでも遷移④はAの後始末を起動する`() = runTest {
        val left = mutableListOf<Subject>()
        val viewModel = authenticatedViewModel(userId = "u-1", onSubjectLeave = { left += it })

        viewModel.completePasskeyLogin(LoginResponse(token = "token-b", user = userWithId("u-2", "bob")))
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
            val viewModel = authenticatedViewModel(
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

        assertEquals(AuthState.Authenticated(userA), viewModel.authState.value)
        assertEquals(0, leaveCount)
        assertEquals(sessionSaveErrorMessage, viewModel.loginErrorMessage.value)
    }

    @Test
    fun `logoutはUnknownからでも後始末を起動せずonSubjectResolvedをnullで1回呼ぶ`() = runTest {
        val resolved = mutableListOf<Subject?>()
        val sent = mutableListOf<String>()
        var leaveCount = 0
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        val viewModel = newViewModel(
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
        val viewModel = newViewModel(
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
        val viewModel = newViewModel(
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
        lateinit var viewModel: AuthViewModel
        fun probeFromOtherThread(label: String) {
            val thread = Thread { viewModel.onUnauthorized("x") }
            thread.start()
            thread.join(5_000)
            if (thread.isAlive) blocked += label
        }
        viewModel = authenticatedViewModel(
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
}
