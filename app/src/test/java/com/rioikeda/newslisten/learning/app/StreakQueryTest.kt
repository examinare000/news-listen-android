package com.rioikeda.newslisten.learning.app

import com.rioikeda.newslisten.core.ApiException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [StreakQuery]（android-query-learning-02・05、android-dec-96・97）。事象は replay 0 なので、収集を `refresh()` より先に始める。 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreakQueryTest {

    private fun TestScope.newQuery(api: FakeLearningApi) =
        StreakQuery(api = api, dispatcher = StandardTestDispatcher(testScheduler))

    private fun TestScope.collectEvents(query: StreakQuery): MutableList<LearningFeedbackEvent> {
        val events = mutableListOf<LearningFeedbackEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { query.feedbackEvents.toList(events) }
        return events
    }

    private fun sequenceOf(api: FakeLearningApi, vararg days: Int) {
        val queue = ArrayDeque(days.toList())
        api.onFetchStreak = { StreakRecord(queue.removeFirst(), true, "2026-07-29") }
    }

    @Test
    fun `android-query-learning-02 再取得した値をstreakへ反映する`() = runTest {
        val api = FakeLearningApi().apply { onFetchStreak = { StreakRecord(3, true, "2026-07-29") } }
        val query = newQuery(api)

        query.refresh()

        val badge = checkNotNull(query.streak.value)
        assertEquals(3, badge.days)
        assertTrue(badge.todayListened)
        assertEquals("2026-07-29", badge.lastListenedDay)
        assertTrue(badge.visible)
        assertFalse(query.loadFailed.value)
    }

    @Test
    fun `android-dec-97 初回取得では鳴らさず既取得値から増えた時だけ事象を1つ出す`() = runTest {
        val api = FakeLearningApi().also { sequenceOf(it, 2, 3) }
        val query = newQuery(api)
        val events = collectEvents(query)

        query.refresh()
        runCurrent()
        assertEquals(emptyList<LearningFeedbackEvent>(), events)

        query.refresh()
        runCurrent()
        assertEquals(listOf(LearningFeedbackEvent.STREAK_INCREASED), events)
    }

    @Test
    fun `android-dec-97 同じ日数と減った日数では事象を出さない`() = runTest {
        val api = FakeLearningApi().also { sequenceOf(it, 2, 3, 3, 2) }
        val query = newQuery(api)
        val events = collectEvents(query)

        repeat(4) {
            query.refresh()
            runCurrent()
        }

        assertEquals(listOf(LearningFeedbackEvent.STREAK_INCREASED), events)
    }

    @Test
    fun `android-dec-97 失敗でstreakがnullになった後の取得は直前が無いので事象を出さない`() = runTest {
        val results = ArrayDeque<() -> StreakRecord>(
            listOf(
                { StreakRecord(2, true, "2026-07-28") },
                { throw ApiException.HttpError(500) },
                { StreakRecord(3, true, "2026-07-29") },
            ),
        )
        val api = FakeLearningApi().apply { onFetchStreak = { results.removeFirst()() } }
        val query = newQuery(api)
        val events = collectEvents(query)

        repeat(3) {
            query.refresh()
            runCurrent()
        }

        assertEquals(emptyList<LearningFeedbackEvent>(), events)
        assertEquals(3, checkNotNull(query.streak.value).days)
    }

    @Test
    fun `android-dec-97 収集者がいない間に出た事象は保持せず後から収集を始めても受け取らない`() = runTest {
        val api = FakeLearningApi().also { sequenceOf(it, 2, 3) }
        val query = newQuery(api)

        query.refresh()
        query.refresh()
        val late = collectEvents(query)
        runCurrent()

        assertEquals(emptyList<LearningFeedbackEvent>(), late)
    }

    @Test
    fun `android-query-learning-02 404はstreakがnullで取得失敗にしない`() = runTest {
        val api = FakeLearningApi().apply { onFetchStreak = { throw ApiException.NotFound() } }
        val query = newQuery(api)
        val events = collectEvents(query)

        query.refresh()
        runCurrent()

        assertNull(query.streak.value)
        assertFalse(query.loadFailed.value)
        assertEquals(emptyList<LearningFeedbackEvent>(), events)
    }

    @Test
    fun `android-query-learning-02 404以外のHttpErrorは取得失敗`() = runTest {
        val api = FakeLearningApi().apply { onFetchStreak = { throw ApiException.HttpError(500) } }
        val query = newQuery(api)

        query.refresh()

        assertNull(query.streak.value)
        assertTrue(query.loadFailed.value)
    }

    @Test
    fun `android-query-learning-02 ネットワークエラーは取得失敗`() = runTest {
        val api = FakeLearningApi().apply { onFetchStreak = { throw ApiException.NetworkError(RuntimeException("offline")) } }
        val query = newQuery(api)

        query.refresh()

        assertNull(query.streak.value)
        assertTrue(query.loadFailed.value)
    }

    @Test
    fun `android-rule-learning-04 表示は日数1以上かつ聴いた日がある時だけ`() = runTest {
        val cases = listOf(
            StreakRecord(0, false, "2026-07-28") to false,
            StreakRecord(3, false, null) to false,
            StreakRecord(3, true, "2026-07-29") to true,
        )
        for ((record, expectedVisible) in cases) {
            val api = FakeLearningApi().apply { onFetchStreak = { record } }
            val query = newQuery(api)

            query.refresh()

            assertEquals("$record", expectedVisible, checkNotNull(query.streak.value).visible)
        }
    }

    @Test
    fun `android-query-learning-02 初期値はstreakがnullで取得失敗でない`() = runTest {
        val query = newQuery(FakeLearningApi())

        assertNull(query.streak.value)
        assertFalse(query.loadFailed.value)
    }
}
