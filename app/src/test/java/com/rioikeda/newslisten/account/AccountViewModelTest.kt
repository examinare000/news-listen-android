package com.rioikeda.newslisten.account

import com.rioikeda.newslisten.auth.AuthViewModel
import com.rioikeda.newslisten.model.PreferencesResponse
import com.rioikeda.newslisten.model.UserResponse
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.ApiException
import com.rioikeda.newslisten.network.InMemorySessionStore
import com.rioikeda.newslisten.preferences.InMemoryPreferencesStore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [AccountViewModel] の挙動検証（表示名編集）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Settings/AccountSettingsView.swift:49-57（表示名編集 UI）、
 * :303-315（saveProfile ロジック）のミラー。
 * フェーズ11 P11 T3。
 */
class AccountViewModelTest {

    private val user = UserResponse(username = "u", role = "member", displayName = "元の表示名")

    /**
     * 認証済み状態の AuthViewModel を組み立てる（[AccountViewModel] のプリフィル・
     * applyProfileUpdate 反映先として使う）。auth 層の FakeApiClient を再利用し、
     * refreshAuth() で Authenticated に遷移させる。
     */
    private fun TestScope.authenticatedAuthViewModel(
        initialUser: UserResponse = user,
    ): AuthViewModel {
        val viewModel = AuthViewModel(
            apiClient = com.rioikeda.newslisten.auth.FakeApiClient(
                onMe = { initialUser },
                onFetchPreferences = {
                    PreferencesResponse(
                        defaultDifficulty = "toeic_800",
                        defaultPlaybackSpeed = 1.0,
                        digestEnabled = false,
                        digestArticleCount = 0,
                    )
                },
            ),
            sessionStore = InMemorySessionStore(initialToken = "token-abc"),
            dispatcher = StandardTestDispatcher(testScheduler),
            preferencesStore = InMemoryPreferencesStore(),
        )
        return viewModel
    }

    private fun TestScope.newViewModel(
        apiClient: ApiClient = FakeApiClient(),
        authViewModel: AuthViewModel = authenticatedAuthViewModel(),
    ): AccountViewModel =
        AccountViewModel(
            apiClient = apiClient,
            authViewModel = authViewModel,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

    // --- 表示名: プリフィル ---

    @Test
    fun 認証後にprefillDisplayNameを呼ぶと表示名が反映される() = runTest {
        val authViewModel = authenticatedAuthViewModel()
        val viewModel = newViewModel(authViewModel = authViewModel)
        // 初期状態は Unknown なので空
        assertEquals("", viewModel.displayName.value)

        authViewModel.refreshAuth()
        viewModel.prefillDisplayName()

        assertEquals("元の表示名", viewModel.displayName.value)
    }

    @Test
    fun 非AuthenticatedならprefillDisplayName後も空である() = runTest {
        val authViewModel = AuthViewModel(
            apiClient = com.rioikeda.newslisten.auth.FakeApiClient(),
            sessionStore = InMemorySessionStore(initialToken = null),
            dispatcher = StandardTestDispatcher(testScheduler),
            preferencesStore = InMemoryPreferencesStore(),
        )
        val viewModel = newViewModel(authViewModel = authViewModel)
        authViewModel.refreshAuth()

        viewModel.prefillDisplayName()

        assertEquals("", viewModel.displayName.value)
    }

    // --- 表示名: 更新 ---

    @Test
    fun saveProfile成功でAuthViewModelのAuthStateへ反映されメッセージが表示される() = runTest {
        val authViewModel = authenticatedAuthViewModel()
        authViewModel.refreshAuth()
        val updatedUser = user.copy(displayName = "新しい表示名")
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onUpdateProfile = { updatedUser }),
            authViewModel = authViewModel,
        )
        viewModel.onDisplayNameChange("新しい表示名")

        viewModel.saveProfile()

        assertEquals(updatedUser, (authViewModel.authState.value as com.rioikeda.newslisten.auth.AuthState.Authenticated).user)
        assertEquals("表示名を更新しました", viewModel.profileMessage.value)
    }

    @Test
    fun saveProfile失敗でエラーメッセージが表示されAuthStateは変わらない() = runTest {
        val authViewModel = authenticatedAuthViewModel()
        authViewModel.refreshAuth()
        val stateBeforeSave = authViewModel.authState.value
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onUpdateProfile = { throw ApiException.HttpError(500) }),
            authViewModel = authViewModel,
        )
        viewModel.onDisplayNameChange("新しい表示名")

        viewModel.saveProfile()

        assertEquals("表示名の更新に失敗しました", viewModel.profileMessage.value)
        assertEquals(stateBeforeSave, authViewModel.authState.value)
    }

    @Test
    fun 初期状態ではメッセージやローディングは空である() = runTest {
        val viewModel = newViewModel()

        assertNull(viewModel.profileMessage.value)
        assertFalse(viewModel.isLoading.value)
    }

    @Test
    fun saveProfileはisLoadingを更新して戻す() = runTest {
        val viewModel = newViewModel(apiClient = FakeApiClient(onUpdateProfile = { user }))

        viewModel.saveProfile()

        assertFalse(viewModel.isLoading.value)
    }
}
