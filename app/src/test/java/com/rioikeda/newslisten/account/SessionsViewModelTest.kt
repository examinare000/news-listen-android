package com.rioikeda.newslisten.account

import com.rioikeda.newslisten.model.RevokeSessionsResponse
import com.rioikeda.newslisten.model.SessionResponse
import com.rioikeda.newslisten.model.SessionsListResponse
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.ApiException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SessionsViewModel] の挙動検証。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Sessions/SessionsViewModel.swift のミラー。
 * フェーズ11 P11 T4（issue #84 相当・ログイン中デバイス管理）。
 */
class SessionsViewModelTest {

    private fun TestScope.newViewModel(
        apiClient: ApiClient = FakeApiClient(),
    ): SessionsViewModel =
        SessionsViewModel(
            apiClient = apiClient,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

    private fun session(
        id: String,
        deviceLabel: String? = "Pixel 8",
        current: Boolean = false,
    ) = SessionResponse(
        id = id,
        deviceLabel = deviceLabel,
        createdAt = "2026-07-01T00:00:00Z",
        lastUsedAt = "2026-07-07T00:00:00Z",
        current = current,
    )

    // --- 一覧取得 ---

    @Test
    fun loadSessions成功でsessionsが更新される() = runTest {
        val sessions = listOf(
            session(id = "s1", current = true),
            session(id = "s2", deviceLabel = null, current = false),
        )
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onListSessions = { SessionsListResponse(sessions) }),
        )

        viewModel.loadSessions()

        assertEquals(sessions, viewModel.sessions.value)
        assertNull(viewModel.errorMessage.value)
        assertFalse(viewModel.isLoading.value)
    }

    @Test
    fun loadSessions失敗でerrorMessageが設定される() = runTest {
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onListSessions = { throw ApiException.HttpError(500) }),
        )

        viewModel.loadSessions()

        assertTrue(viewModel.sessions.value.isEmpty())
        assertTrue(viewModel.errorMessage.value != null)
    }

    @Test
    fun loadSessionsは呼び出しのたびにrevokedOthersCountをリセットする() = runTest {
        // iOS SessionsViewModel.swift:32 準拠: 再読み込み時に一括失効のフィードバックを消す。
        val viewModel = newViewModel(
            apiClient = FakeApiClient(
                onListSessions = { SessionsListResponse(listOf(session(id = "s1", current = true))) },
                onRevokeOtherSessions = { RevokeSessionsResponse(revokedCount = 2) },
            ),
        )
        viewModel.loadSessions()
        viewModel.revokeOtherSessions()
        assertEquals(2, viewModel.revokedOthersCount.value)

        viewModel.loadSessions()

        assertNull(viewModel.revokedOthersCount.value)
    }

    // --- 個別失効 ---

    @Test
    fun revokeSession成功でsessionsからローカル除去される() = runTest {
        val remaining = session(id = "keep", current = true)
        val viewModel = newViewModel(
            apiClient = FakeApiClient(
                onListSessions = { SessionsListResponse(listOf(remaining, session(id = "remove"))) },
                onRevokeSession = { },
            ),
        )
        viewModel.loadSessions()

        viewModel.revokeSession(id = "remove")

        assertEquals(listOf(remaining), viewModel.sessions.value)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun revokeSessionは404以外の失敗でerrorMessageが設定されsessionsは変わらない() = runTest {
        val existing = listOf(session(id = "s1", current = true), session(id = "s2"))
        val viewModel = newViewModel(
            apiClient = FakeApiClient(
                onListSessions = { SessionsListResponse(existing) },
                onRevokeSession = { throw ApiException.HttpError(500) },
            ),
        )
        viewModel.loadSessions()

        viewModel.revokeSession(id = "s2")

        assertEquals(existing, viewModel.sessions.value)
        assertTrue(viewModel.errorMessage.value != null)
    }

    @Test
    fun revokeSessionはcurrentセッションでもガードなくAPIを呼ぶ() = runTest {
        // iOS SessionsViewModel.swift:48-63 準拠: VM 層に current ガードは無く、
        // 失効ボタン非表示という UI 側の導線のみで防いでいる（AccountSettingsView.swift:194）。
        val apiClient = FakeApiClient(
            onListSessions = { SessionsListResponse(listOf(session(id = "s1", current = true))) },
            onRevokeSession = { },
        )
        val viewModel = newViewModel(apiClient = apiClient)
        viewModel.loadSessions()

        viewModel.revokeSession(id = "s1")

        assertEquals(1, apiClient.revokeSessionCallCount)
        assertTrue(viewModel.sessions.value.isEmpty())
    }

    // --- 一括失効 ---

    @Test
    fun revokeOtherSessions成功でrevokedOthersCountが設定され再取得される() = runTest {
        var listCallCount = 0
        val viewModel = newViewModel(
            apiClient = FakeApiClient(
                onListSessions = {
                    listCallCount++
                    SessionsListResponse(listOf(session(id = "s1", current = true)))
                },
                onRevokeOtherSessions = { RevokeSessionsResponse(revokedCount = 3) },
            ),
        )

        viewModel.revokeOtherSessions()

        assertEquals(3, viewModel.revokedOthersCount.value)
        assertEquals(1, listCallCount)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun revokeOtherSessions失敗でerrorMessageが設定される() = runTest {
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onRevokeOtherSessions = { throw ApiException.HttpError(500) }),
        )

        viewModel.revokeOtherSessions()

        assertNull(viewModel.revokedOthersCount.value)
        assertTrue(viewModel.errorMessage.value != null)
    }

    // --- hasOtherSessions ---

    @Test
    fun hasOtherSessionsは非currentが無ければfalse() = runTest {
        val viewModel = newViewModel(
            apiClient = FakeApiClient(
                onListSessions = { SessionsListResponse(listOf(session(id = "s1", current = true))) },
            ),
        )

        viewModel.loadSessions()

        assertFalse(viewModel.hasOtherSessions.value)
    }

    @Test
    fun hasOtherSessionsは非currentがあればtrue() = runTest {
        val viewModel = newViewModel(
            apiClient = FakeApiClient(
                onListSessions = {
                    SessionsListResponse(listOf(session(id = "s1", current = true), session(id = "s2")))
                },
            ),
        )

        viewModel.loadSessions()

        assertTrue(viewModel.hasOtherSessions.value)
    }
}
