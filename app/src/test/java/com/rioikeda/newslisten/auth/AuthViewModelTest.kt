package com.rioikeda.newslisten.auth

import com.rioikeda.newslisten.model.LoginResponse
import com.rioikeda.newslisten.model.PreferencesResponse
import com.rioikeda.newslisten.model.UserResponse
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.ApiException
import com.rioikeda.newslisten.network.InMemorySessionStore
import com.rioikeda.newslisten.network.SessionStore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
 */
class AuthViewModelTest {

    private val user = UserResponse(username = "u", role = "member", displayName = "U")
    private val preferences = PreferencesResponse(
        defaultDifficulty = "toeic_800",
        defaultPlaybackSpeed = 1.5,
        digestEnabled = true,
        digestArticleCount = 5,
    )

    /** テスト対象の生成を共通化する。dispatcher は runTest の testScheduler に紐付ける。 */
    private fun TestScope.newViewModel(
        apiClient: ApiClient = FakeApiClient(),
        sessionStore: SessionStore = InMemorySessionStore(),
    ): AuthViewModel = AuthViewModel(apiClient, sessionStore, StandardTestDispatcher(testScheduler))

    // --- refreshAuth ---

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
    fun トークンありでme401ならトークン破棄してUnauthenticatedになる() = runTest {
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onMe = { throw ApiException.HttpError(401) }),
            sessionStore = sessionStore,
        )

        viewModel.refreshAuth()

        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
        assertNull(sessionStore.load())
    }

    @Test
    fun トークンありでmeがNetworkErrorでもトークン破棄してUnauthenticatedになる() = runTest {
        val sessionStore = InMemorySessionStore(initialToken = "token-abc")
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onMe = { throw ApiException.NetworkError(RuntimeException("offline")) }),
            sessionStore = sessionStore,
        )

        viewModel.refreshAuth()

        assertEquals(AuthState.Unauthenticated, viewModel.authState.value)
        assertNull(sessionStore.load())
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

    // --- login ---

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
    fun login401ならユーザーIDまたはパスワードが正しくありませんと表示する() = runTest {
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onLogin = { _, _ -> throw ApiException.HttpError(401) }),
        )

        viewModel.login("u", "wrong-password")

        assertEquals("ユーザーIDまたはパスワードが正しくありません", viewModel.loginErrorMessage.value)
        assertEquals(AuthState.Unknown, viewModel.authState.value)
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
}
