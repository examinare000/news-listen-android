package com.rioikeda.newslisten.engagement

import com.rioikeda.newslisten.model.ListeningStreakResponse
import com.rioikeda.newslisten.network.ApiException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningStreakStoreTest {
    @Test
    fun `再取得した値を共有StateFlowへ反映する`() = runTest {
        val expected = ListeningStreakResponse(3, true, "2026-07-29")
        val store = ApiListeningStreakStore(
            fetchListeningStreak = { expected },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        store.refresh()

        assertEquals(expected, store.listeningStreak.value)
        assertFalse(store.loadFailed.value)
    }

    @Test
    fun `初回取得では鳴らさず既取得値から増えた時だけ通知する`() = runTest {
        val responses = ArrayDeque(
            listOf(
                ListeningStreakResponse(2, true, "2026-07-28"),
                ListeningStreakResponse(3, true, "2026-07-29"),
            )
        )
        var increaseCount = 0
        val store = ApiListeningStreakStore(
            fetchListeningStreak = { responses.removeFirst() },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        store.onStreakIncreased = { increaseCount++ }

        store.refresh()
        store.refresh()

        assertEquals(1, increaseCount)
    }

    // --- 要件4: 失敗系テスト ---

    @Test
    fun `404エラーの場合loadFailedはfalse（実装との符合確認）`() = runTest {
        val store = ApiListeningStreakStore(
            fetchListeningStreak = { throw ApiException.HttpError(404) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        store.refresh()

        assertNull(store.listeningStreak.value)
        assertFalse(store.loadFailed.value)
    }

    @Test
    fun `404以外のHttpExceptionの場合loadFailedはtrue`() = runTest {
        val store = ApiListeningStreakStore(
            fetchListeningStreak = { throw ApiException.HttpError(500) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        store.refresh()

        assertNull(store.listeningStreak.value)
        assertTrue(store.loadFailed.value)
    }

    @Test
    fun `ネットワークエラーの場合loadFailedはtrue`() = runTest {
        val store = ApiListeningStreakStore(
            fetchListeningStreak = { throw ApiException.NetworkError(RuntimeException("offline")) },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        store.refresh()

        assertNull(store.listeningStreak.value)
        assertTrue(store.loadFailed.value)
    }
}
