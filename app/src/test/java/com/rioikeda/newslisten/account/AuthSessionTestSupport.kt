package com.rioikeda.newslisten.account

import com.rioikeda.newslisten.auth.app.AuthSession
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.AuthApiAdapter
import com.rioikeda.newslisten.network.InMemorySessionStore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/**
 * `AccountCommands` を組む test が使う [AuthSession]。hook 4 つは何もしない lambda を明示する
 * （[AuthSession] の hook には既定値がない）。
 */
fun TestScope.testAuthSession(
    apiClient: ApiClient = com.rioikeda.newslisten.auth.FakeApiClient(),
    sessionStore: InMemorySessionStore = InMemorySessionStore(),
): AuthSession =
    AuthSession(
        authApi = AuthApiAdapter(apiClient),
        sessionStore = sessionStore,
        dispatcher = StandardTestDispatcher(testScheduler),
        onSubjectLeave = {},
        onSessionRestored = {},
        onAuthenticated = {},
        onSubjectResolved = {},
    )
