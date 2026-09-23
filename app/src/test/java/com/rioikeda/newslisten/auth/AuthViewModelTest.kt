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
 * android S0（認証失効の型分離と検知）: `onLogoutCleanup` を `onSubjectLeave` へ改名（SG-R18）。
 * 401 は [ApiException.Unauthorized] 型で受け、一時障害とは区別する（CI-T10）。
 */
class AuthViewModelTest {

    private val user = UserResponse(username = "u", role = "member", displayName = "U")
    private val preferences = PreferencesResponse(
        defaultDifficulty = "toeic_800",
        defaultPlaybackSpeed = 1.5,
        digestEnabled = true,
        digestArticleCount = 5,
    )

    /** 保存失敗時のエラー文言（CI-T14, CI-S0-3。spec §3 wording）。 */
    private val sessionSaveErrorMessage = "ログイン状態を端末に保存できませんでした。もう一度お試しください"

    /** テスト対象の生成を共通化する。dispatcher は runTest の testScheduler に紐付ける。 */
    private fun TestScope.newViewModel(
        apiClient: ApiClient = FakeApiClient(),
        sessionStore: SessionStore = InMemorySessionStore(),
        preferencesStore: PreferencesStore = InMemoryPreferencesStore(),
        onSubjectLeave: suspend () -> Unit = {},
        onAuthenticated: () -> Unit = {},
    ): AuthViewModel =
        AuthViewModel(
            apiClient = apiClient,
            sessionStore = sessionStore,
            dispatcher = StandardTestDispatcher(testScheduler),
            preferencesStore = preferencesStore,
            onSubjectLeave = onSubjectLeave,
            onAuthenticated = onAuthenticated,
        )

    // --- refreshAuth（CI-T10, T-T10） ---

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
        // verifies: CI-T10
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
        assertFalse("失効経路は後始末を呼ばない（order:12）", subjectLeaveCalled)
    }

    @Test
    fun network_トークンありでmeがNetworkErrorならUnknownのままトークンを保持しlastFailureを立てる() = runTest {
        // verifies: CI-T10（既存 :95 の反転。一時障害でトークンを破棄しない仕様変更）
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
    fun http500_トークンありでmeがHttpError500ならUnknownのままトークンを保持しlastFailureを立てる() = runTest {
        // verifies: CI-T10
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
    fun decoding_トークンありでmeがDecodingErrorならUnknownのままトークンを保持しlastFailureを立てる() = runTest {
        // verifies: CI-T10
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
        // verifies: CI-T10
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
        // verifies: CI-T10（F-2: 画面回転での LaunchedEffect 再実行）
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

    // --- login（CI-T21, T-T21） ---

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
    fun unauthorized_loginがUnauthorizedを受けたらユーザーIDまたはパスワードが正しくありませんと表示する() = runTest {
        // verifies: CI-T21（既存 :179 の置換。leakage guard: AuthViewModel は HttpError.code を比較しない）
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
        // verifies: CI-T21（401 以外の HttpError は専用文言にならない）
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

    // --- login / completePasskeyLogin の保存失敗（CI-T14, CI-S0-3） ---

    @Test
    fun login_save_fail_saveが失敗したらAuthenticatedにならずエラー文言を表示する() = runTest {
        // verifies: CI-T14
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
    fun logoutはAPI失敗でもトークンを破棄しUnauthenticatedになる() = runTest {
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onLogout = { throw ApiException.NetworkError(RuntimeException("offline")) }),
            sessionStore = sessionStore,
        )

        viewModel.logout()

        assertNull(sessionStore.load())
        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
    }

    // --- logout: キャッシュクリーンアップ（spec §6.3 共有端末対応、フェーズ8-D。SG-R18 で onSubjectLeave に改名） ---

    @Test
    fun logoutでonSubjectLeaveが呼ばれる() = runTest {
        var subjectLeaveCalled = false
        val viewModel = newViewModel(onSubjectLeave = { subjectLeaveCalled = true })

        viewModel.logout()

        assertTrue(subjectLeaveCalled)
    }

    @Test
    fun logoutはonSubjectLeaveが例外を投げてもトークンを破棄しUnauthenticatedになる() = runTest {
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        val viewModel = newViewModel(
            sessionStore = sessionStore,
            onSubjectLeave = { throw RuntimeException("cache cleanup failed") },
        )

        viewModel.logout()

        assertNull(sessionStore.load())
        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
    }

    @Test
    fun loginではonSubjectLeaveは呼ばれない() = runTest {
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
        assertFalse("失効経路は後始末を呼ばない（order:12）", subjectLeaveCalled)
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

    // --- logout 実行中の失効通知の抑止（CI-S0-10, T-S0-10。SG-S0-7 = (a)、order:95-99 で user が確定） ---

    @Test
    fun notice_during_logout_後始末の実行中に届いた失効通知は完了まで無視される() = runTest {
        // verifies: CI-S0-10
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        var recordedState: AuthState? = null
        var recordedToken: String? = null
        lateinit var viewModel: AuthViewModel
        viewModel = newViewModel(
            apiClient = FakeApiClient(onMe = { user }, onFetchPreferences = { preferences }),
            sessionStore = sessionStore,
            onSubjectLeave = {
                viewModel.onUnauthorized("token-abc")
                recordedState = viewModel.authState.value
                recordedToken = sessionStore.load()
            },
        )
        viewModel.refreshAuth()

        viewModel.logout()

        assertEquals(AuthState.Authenticated(user), recordedState)
        assertEquals("token-abc", recordedToken)
        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
        assertNull(sessionStore.load())
    }

    @Test
    fun notice_during_server_logout_apiClient_logoutの実行中に届いた失効通知も無視される() = runTest {
        // verifies: CI-S0-10（server がセッションを削除した直後に並行要求が 401 を受ける経路 B の入口を塞ぐ）
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        var recordedState: AuthState? = null
        var recordedToken: String? = null
        lateinit var viewModel: AuthViewModel
        viewModel = newViewModel(
            apiClient = FakeApiClient(
                onMe = { user },
                onFetchPreferences = { preferences },
                onLogout = {
                    viewModel.onUnauthorized("token-abc")
                    recordedState = viewModel.authState.value
                    recordedToken = sessionStore.load()
                },
            ),
            sessionStore = sessionStore,
        )
        viewModel.refreshAuth()

        viewModel.logout()

        assertEquals(AuthState.Authenticated(user), recordedState)
        assertEquals("token-abc", recordedToken)
        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
        assertNull(sessionStore.load())
    }

    @Test
    fun order_pin_後始末の完了前は遷移せず完了後にUnauthenticatedになる() = runTest {
        // verifies: CI-S0-10（order:57「後始末の完了後に遷移」の現行順序を維持することの固定）
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        var recordedState: AuthState? = null
        var recordedToken: String? = null
        lateinit var viewModel: AuthViewModel
        viewModel = newViewModel(
            apiClient = FakeApiClient(onMe = { user }, onFetchPreferences = { preferences }),
            sessionStore = sessionStore,
            onSubjectLeave = {
                delay(1_000)
                recordedState = viewModel.authState.value
                recordedToken = sessionStore.load()
            },
        )
        viewModel.refreshAuth()

        launch { viewModel.logout() }
        runCurrent()

        assertEquals(AuthState.Authenticated(user), viewModel.authState.value)
        assertEquals("token-abc", sessionStore.load())

        advanceUntilIdle()

        assertEquals(AuthState.Authenticated(user), recordedState)
        assertEquals("token-abc", recordedToken)
        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
        assertNull(sessionStore.load())
    }

    @Test
    fun guard_released_apiClient_logoutの途中でcancelされてもガードは外れ以後の失効通知は処理される() = runTest {
        // verifies: CI-S0-10（F10: 減算が finally に無いと以後の失効通知を捨て続ける欠陥を判別する）
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        var subjectLeaveCalled = false
        val viewModel = newViewModel(
            apiClient = FakeApiClient(
                onMe = { user },
                onFetchPreferences = { preferences },
                onLogout = { delay(1_000) },
            ),
            sessionStore = sessionStore,
            onSubjectLeave = { subjectLeaveCalled = true },
        )
        viewModel.refreshAuth()

        val job = launch { viewModel.logout() }
        runCurrent()
        job.cancel()
        advanceUntilIdle()

        assertEquals(AuthState.Authenticated(user), viewModel.authState.value)
        assertEquals("token-abc", sessionStore.load())
        assertFalse(subjectLeaveCalled)

        viewModel.onUnauthorized("token-abc")

        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
        assertNull(sessionStore.load())
    }

    @Test
    fun cancel_during_cleanup_後始末の途中でcancelされても現行どおりclearまで進む() = runTest {
        // verifies: CI-S0-10（現行の挙動の固定。onSubjectLeave 内の CancellationException は既存の catch に吸われる）
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onMe = { user }, onFetchPreferences = { preferences }),
            sessionStore = sessionStore,
            onSubjectLeave = { delay(1_000) },
        )
        viewModel.refreshAuth()

        val job = launch { viewModel.logout() }
        runCurrent()
        job.cancel()
        advanceUntilIdle()

        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
        assertNull(sessionStore.load())
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
        // verifies: CI-S0-8, CI-T10, CI-S0-6
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
        // verifies: CI-T10（third_vote_completion GPT-6 反証1: 並行 refreshAuth の
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
}
