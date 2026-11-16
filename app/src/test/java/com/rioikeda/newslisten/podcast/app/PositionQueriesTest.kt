package com.rioikeda.newslisten.podcast.app

import com.rioikeda.newslisten.podcast.FakePodcastApi
import com.rioikeda.newslisten.podcast.domain.PositionRecord
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 再開位置の候補（端末の記録とサーバーの値の新しい方。android-rule-playback-12〜14・android-query-playback-06）。読むだけで書かず送らない。 */
@OptIn(ExperimentalCoroutinesApi::class)
class PositionQueriesTest {

    private val t1 = Instant.parse("2026-11-16T01:02:03.456Z")
    private val t2 = Instant.parse("2026-11-16T01:02:04.456Z")
    private val iso1 = "2026-11-16T01:02:03.456Z"
    private val iso2 = "2026-11-16T01:02:04.456Z"

    private val store = FakePositionStore()
    private val api = FakePodcastApi(onUpdatePlaybackPosition = { id, _ -> positionEpisode(id) })
    private val queries = PositionQueries(store)

    private fun deviceRecord(seconds: Double, at: Instant) = PositionRecord("u1", "p1", seconds, at, unsent = true)

    /** 候補を読んだ後、書込と送信が一切起きていないことを確かめる（全本に共通の Then）。 */
    private fun assertNothingWrittenOrSent() {
        assertTrue("store.write が呼ばれた: ${store.writes}", store.writes.isEmpty())
        assertTrue("markSent が呼ばれた", store.markSentCalls.isEmpty())
        assertTrue("adoptIfNewer が呼ばれた", store.adoptCalls.isEmpty())
        assertTrue("PATCH が送られた", api.updatePlaybackPositionCalls.isEmpty())
    }

    private fun TestScope.commandsFor(subject: String?) = PositionCommands(
        podcastApi = api,
        store = store,
        clock = TestClock(t2),
        currentSubject = { subject },
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `android-possync-23 shared-resume-08 端末の記録が新しければ端末の位置を候補にする`() = runTest {
        store.seed(deviceRecord(40.0, t2))
        assertTrue("Given: 書込・送信の記録は空", store.writes.isEmpty() && api.updatePlaybackPositionCalls.isEmpty())

        val candidate = queries.resumeCandidate("u1", positionEpisode("p1", 10.0, iso1))

        assertEquals(40.0, candidate, 0.0)
        assertNothingWrittenOrSent()

        // 正方向の対照: 同じ store と api で record を呼ぶと、書込と PATCH が 1 件ずつ起きる。
        val commands = commandsFor("u1")
        commands.record("u1", "p1", 12.5)
        advanceUntilIdle()
        assertEquals(1, store.writes.size)
        assertEquals(listOf("p1" to 12.5), api.updatePlaybackPositionCalls)
        assertEquals("読みの回数は FakePositionStore.readCalls で数える", 1, store.readCalls)
    }

    @Test
    fun `android-possync-23 shared-resume-09 サーバーが新しければサーバーの位置を候補にする`() = runTest {
        store.seed(deviceRecord(40.0, t1))

        val candidate = queries.resumeCandidate("u1", positionEpisode("p1", 90.0, iso2))

        assertEquals(90.0, candidate, 0.0)
        assertNothingWrittenOrSent()
    }

    @Test
    fun `android-possync-23 shared-resume-10 サーバーの時刻が欠けていれば端末の位置を候補にする`() = runTest {
        store.seed(deviceRecord(40.0, t1))

        val candidate = queries.resumeCandidate("u1", positionEpisode("p1", 90.0, null))

        assertEquals(40.0, candidate, 0.0)
        assertNothingWrittenOrSent()
    }

    @Test
    fun `android-possync-23 shared-resume-11 同じ時刻なら端末の位置を候補にする`() = runTest {
        store.seed(deviceRecord(40.0, t1))

        val candidate = queries.resumeCandidate("u1", positionEpisode("p1", 90.0, iso1))

        assertEquals(40.0, candidate, 0.0)
        assertNothingWrittenOrSent()
    }

    @Test
    fun `android-possync-23 shared-resume-12 端末の記録が無ければサーバーの値を候補にする`() = runTest {
        assertTrue("Given: 端末の記録は無い", store.all().isEmpty())

        assertEquals(90.0, queries.resumeCandidate("u1", positionEpisode("p1", 90.0, null)), 0.0)
        assertEquals(0.0, queries.resumeCandidate("u1", positionEpisode("p1", 0.0, null)), 0.0)
        assertEquals("時刻つきでも端末が無ければサーバー", 90.0, queries.resumeCandidate("u1", positionEpisode("p1", 90.0, iso2)), 0.0)

        assertNothingWrittenOrSent()
    }

    @Test
    fun `android-possync-23 shared-sync-10 latestRecordは主体とepisodeの端末の記録をそのまま返す`() = runTest {
        store.seed(deviceRecord(40.0, t2))

        assertEquals(deviceRecord(40.0, t2), queries.latestRecord("u1", "p1"))
        assertEquals("別の episode の記録は無い", null, queries.latestRecord("u1", "p2"))
        assertNothingWrittenOrSent()
    }

    @Test
    fun `android-possync-23 shared-sync-10 latestRecordは主体がnullなら読まずにnullを返す`() = runTest {
        store.seed(deviceRecord(40.0, t2))
        assertEquals("Given: 読みは 0 回", 0, store.readCalls)

        assertEquals(null, queries.latestRecord(null, "p1"))

        assertEquals("端末の記録を読んでいない", 0, store.readCalls)
    }

    @Test
    fun `android-possync-23 shared-sync-10 latestRecordは読みのIOExceptionをnullに倒す`() = runTest {
        store.seed(deviceRecord(40.0, t2))
        store.failNextRead = true

        assertEquals("例外を出さず null", null, queries.latestRecord("u1", "p1"))

        assertEquals("読みは 1 回試みた", 1, store.readCalls)
        // 正方向の対照: 失敗の印は 1 回で消え、次の読みは記録を返す。
        assertEquals(deviceRecord(40.0, t2), queries.latestRecord("u1", "p1"))
    }

    @Test
    fun `android-possync-23 shared-sync-10 latestRecordは書かず送らない`() = runTest {
        store.seed(deviceRecord(40.0, t2))
        assertTrue("Given: 書込・送信の記録は空", store.writes.isEmpty() && api.updatePlaybackPositionCalls.isEmpty())

        queries.latestRecord("u1", "p1")

        assertNothingWrittenOrSent()
        // 正方向の対照: 同じ store と api で record を呼ぶと、書込と PATCH が 1 件ずつ起きる。
        commandsFor("u1").record("u1", "p1", 12.5)
        advanceUntilIdle()
        assertEquals(1, store.writes.size)
        assertEquals(listOf("p1" to 12.5), api.updatePlaybackPositionCalls)
    }

    @Test
    fun `android-possync-23 主体がnullなら端末の記録を読まずサーバーの値を候補にする`() = runTest {
        store.seed(deviceRecord(40.0, t2))
        assertEquals("Given: 読みは 0 回", 0, store.readCalls)

        val candidate = queries.resumeCandidate(null, positionEpisode("p1", 10.0, iso1))

        assertEquals(10.0, candidate, 0.0)
        assertEquals("端末の記録を読んでいない", 0, store.readCalls)
        assertNothingWrittenOrSent()
    }
}
