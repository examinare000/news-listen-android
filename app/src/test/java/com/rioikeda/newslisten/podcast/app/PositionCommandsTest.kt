package com.rioikeda.newslisten.podcast.app

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.podcast.FakePodcastApi
import com.rioikeda.newslisten.podcast.domain.PositionRecord
import com.rioikeda.newslisten.podcast.domain.ServerPosition
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * [PositionCommands]（位置の記録・端末保存・送信と再送・取り込み・主体離脱）の検証。
 * 主体は可変の [subject]、時刻は [TestClock]、通信は [FakePodcastApi]、保存は [FakePositionStore]。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PositionCommandsTest {

    private val t0 = Instant.parse("2026-11-16T01:02:02.456Z")
    private val t1 = Instant.parse("2026-11-16T01:02:03.456Z")
    private val t2 = Instant.parse("2026-11-16T01:02:04.456Z")

    private val clock = TestClock(t1)
    private val store = FakePositionStore()
    private var subject: String? = "u1"

    private fun okApi() = FakePodcastApi(onUpdatePlaybackPosition = { id, _ -> positionEpisode(id) })

    private fun TestScope.commandsFor(api: FakePodcastApi, positionStore: FakePositionStore = store) = PositionCommands(
        podcastApi = api,
        store = positionStore,
        clock = clock,
        currentSubject = { subject },
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun unsentRecord(subjectId: String, episodeId: String, seconds: Double, at: Instant) =
        PositionRecord(subjectId, episodeId, seconds, at, unsent = true)

    @Test
    fun `android-possync-07 shared-sync-03 送信が失敗しても未送信のまま保持する（NetworkError）`() = runTest {
        val api = FakePodcastApi(onUpdatePlaybackPosition = { _, _ -> throw ApiException.NetworkError(IOException("offline")) })
        val commands = commandsFor(api)
        assertTrue("Given: store は空", store.all().isEmpty())

        val recordedAt = commands.record("u1", "p1", 12.5)
        advanceUntilIdle()

        assertEquals(t1, recordedAt)
        assertEquals(PositionRecord("u1", "p1", 12.5, t1, unsent = true), store.recordOf("u1", "p1"))
        assertEquals(1, api.updatePlaybackPositionCalls.size)
    }

    @Test
    fun `android-possync-08 shared-sync-03 接続の復帰で再送して送信済みにする`() = runTest {
        val api = okApi()
        val commands = commandsFor(api)
        store.seed(unsentRecord("u1", "p1", 12.5, t1))
        clock.now = t2 // 再送は記録時刻を付け直さない
        assertTrue("Given: 送信 0", api.updatePlaybackPositionCalls.isEmpty())

        commands.resendUnsent("u1")

        assertEquals(listOf("p1" to 12.5), api.updatePlaybackPositionCalls)
        assertEquals("同じ記録時刻のまま再送", listOf(t1), api.updatePlaybackPositionRecordedAts)
        assertFalse(store.recordOf("u1", "p1")!!.unsent)
    }

    @Test
    fun `android-possync-09 shared-sync-03 主体の確定で未送信を再送する`() = runTest {
        val api = okApi()
        val commands = commandsFor(api)
        store.seed(unsentRecord("u1", "p1", 12.5, t1))
        assertTrue("Given: 送信 0", api.updatePlaybackPositionCalls.isEmpty())

        commands.onSubjectResolved("u1")
        advanceUntilIdle()

        assertEquals(listOf("p1" to 12.5), api.updatePlaybackPositionCalls)
        assertFalse(store.recordOf("u1", "p1")!!.unsent)
    }

    @Test
    fun `android-possync-10 shared-sync-03 次の記録の送信で未送信を一緒に再送する`() = runTest {
        val api = okApi()
        val commands = commandsFor(api)
        store.seed(unsentRecord("u1", "p2", 7.0, t0))
        assertTrue("Given: 送信 0", api.updatePlaybackPositionCalls.isEmpty())

        commands.record("u1", "p1", 12.5)
        advanceUntilIdle()

        assertEquals(setOf("p1" to 12.5, "p2" to 7.0), api.updatePlaybackPositionCalls.toSet())
        assertEquals("p2 は元の記録時刻で再送", t0, api.updatePlaybackPositionRecordedAts[api.updatePlaybackPositionCalls.indexOf("p2" to 7.0)])
        assertFalse(store.recordOf("u1", "p1")!!.unsent)
        assertFalse(store.recordOf("u1", "p2")!!.unsent)
    }

    @Test
    fun `android-possync-11 shared-sync-04 応答の待ちの間に書かれた新しい記録を古い応答で送信済みにしない`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val api = FakePodcastApi(
            onUpdatePlaybackPosition = { id, _ ->
                calls++
                if (calls == 1) {
                    gate.await()
                    positionEpisode(id)
                } else {
                    throw ApiException.NetworkError(IOException("offline")) // R2 は未送信のまま残す
                }
            },
        )
        val commands = commandsFor(api)
        clock.now = t1
        val r1 = launch { commands.record("u1", "p1", 10.0) }
        runCurrent()
        assertEquals("Given: R1 は書かれ、応答を待っている", unsentRecord("u1", "p1", 10.0, t1), store.recordOf("u1", "p1"))

        clock.now = t2
        commands.record("u1", "p1", 20.0)
        gate.complete(Unit)
        advanceUntilIdle()
        r1.join()

        assertEquals("R2 は未送信のまま", unsentRecord("u1", "p1", 20.0, t2), store.recordOf("u1", "p1"))
        assertEquals(listOf(Triple("u1", "p1", t1)), store.markSentCalls)
        assertEquals(listOf(false), store.markSentResults)
    }

    @Test
    fun `android-possync-11 shared-sync-04 新しい記録が無ければ応答で送信済みになる（対照）`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = FakePodcastApi(onUpdatePlaybackPosition = { id, _ -> gate.await(); positionEpisode(id) })
        val commands = commandsFor(api)
        val r1 = launch { commands.record("u1", "p1", 10.0) }
        runCurrent()
        assertTrue("Given: 応答を待つ間は未送信", store.recordOf("u1", "p1")!!.unsent)

        gate.complete(Unit)
        advanceUntilIdle()
        r1.join()

        assertFalse(store.recordOf("u1", "p1")!!.unsent)
        assertEquals(listOf(true), store.markSentResults)
    }

    @Test
    fun `android-possync-12 shared-sync-05 404と422だけ送信済みにしほかの失敗は未送信で残す`() = runTest {
        val permanent = listOf<ApiException>(ApiException.NotFound(), ApiException.UnprocessableInput())
        val retained = listOf<ApiException>(
            ApiException.InvalidInput(), ApiException.Unauthorized(), ApiException.HttpError(403), ApiException.Conflict(),
            ApiException.RateLimited(null), ApiException.ServerError(500), ApiException.NetworkError(IOException("x")),
            ApiException.DecodingError(RuntimeException("x")),
        )
        for ((failure, expectSent) in permanent.map { it to true } + retained.map { it to false }) {
            val caseStore = FakePositionStore()
            val api = FakePodcastApi(onUpdatePlaybackPosition = { _, _ -> throw failure })
            val commands = commandsFor(api, caseStore)

            commands.record("u1", "p1", 12.5)
            advanceUntilIdle()

            val stored = caseStore.recordOf("u1", "p1")
            assertEquals("${failure::class.simpleName}: 記録は残る", 12.5, stored!!.seconds, 0.0)
            assertEquals("${failure::class.simpleName}: unsent", !expectSent, stored.unsent)
        }
    }

    @Test
    fun `android-possync-13 保存の失敗でも送信を試みて例外を投げない`() = runTest {
        val api = okApi()
        val commands = commandsFor(api)
        store.failNextWrite = true

        val recordedAt = commands.record("u1", "p1", 12.5)
        advanceUntilIdle()

        assertEquals(t1, recordedAt)
        assertEquals(listOf("p1" to 12.5), api.updatePlaybackPositionCalls)
        assertEquals(listOf(t1), api.updatePlaybackPositionRecordedAts)
        assertTrue("書込は失敗した", store.writes.isEmpty())
    }

    @Test
    fun `android-possync-18 shared-sync-06 サーバーの時刻が新しければ端末の記録を置き換え未送信を外す`() = runTest {
        val commands = commandsFor(okApi())
        store.seed(unsentRecord("u1", "p1", 40.0, t1))

        commands.adoptServerValue("u1", "p1", ServerPosition(90.0, t2))

        assertEquals(PositionRecord("u1", "p1", 90.0, t2, unsent = false), store.recordOf("u1", "p1"))
    }

    @Test
    fun `android-possync-18 shared-sync-06 古い・同じ時刻・時刻なしのサーバーの値では端末の記録を変えない`() = runTest {
        val commands = commandsFor(okApi())
        val original = unsentRecord("u1", "p1", 40.0, t1)
        store.seed(original)

        for (server in listOf(ServerPosition(90.0, t0), ServerPosition(90.0, t1), ServerPosition(90.0, null))) {
            commands.adoptServerValue("u1", "p1", server)
            assertEquals("$server", original, store.recordOf("u1", "p1"))
        }
        assertEquals("取り込みは試みられた（vacuous でない）", 3, store.adoptCalls.size)
    }

    @Test
    fun `android-possync-18 shared-sync-06 書込の応答の時刻が新しければ取り込み同じ・古い・nullなら変えず送信済みにする`() = runTest {
        // 記録 R（10 秒・t1）を送り、応答 (99 秒・応答の時刻) が返る。
        suspend fun respond(recordedAt: String?): PositionRecord {
            val caseStore = FakePositionStore()
            val api = FakePodcastApi(onUpdatePlaybackPosition = { id, _ -> positionEpisode(id, 99.0, recordedAt) })
            commandsFor(api, caseStore).record("u1", "p1", 10.0)
            advanceUntilIdle()
            return caseStore.recordOf("u1", "p1")!!
        }

        assertEquals("新しい（他の端末が先）", PositionRecord("u1", "p1", 99.0, t2, unsent = false), respond("2026-11-16T01:02:04.456Z"))
        assertEquals("同じ", PositionRecord("u1", "p1", 10.0, t1, unsent = false), respond("2026-11-16T01:02:03.456Z"))
        assertEquals("古い", PositionRecord("u1", "p1", 10.0, t1, unsent = false), respond("2026-11-16T01:02:02.456Z"))
        assertEquals("時刻なし", PositionRecord("u1", "p1", 10.0, t1, unsent = false), respond(null))
    }

    @Test
    fun `android-possync-27 shared-leave-11 離脱の手順は未送信を含め送らずに消す`() = runTest {
        val api = okApi()
        val commands = commandsFor(api)
        store.seed(unsentRecord("uA", "p1", 12.5, t1))
        store.seed(unsentRecord("uB", "p1", 3.0, t1))
        subject = "uA"

        commands.discardSubject("uA")
        advanceUntilIdle()

        assertNull(store.recordOf("uA", "p1"))
        assertEquals("B の記録は残る", unsentRecord("uB", "p1", 3.0, t1), store.recordOf("uB", "p1"))
        assertTrue("消す記録を送らない", api.updatePlaybackPositionCalls.isEmpty())
    }

    @Test
    fun `android-possync-28 shared-leave-14 後始末の後に届く旧主体の応答は別の主体の記録を書き換えない（A→B の直接交代）`() = runTest {
        val gate = CompletableDeferred<Unit>()
        // 応答は B の記録を新しい値で置き換えうる内容（取り込まれたら露見する）。
        val api = FakePodcastApi(onUpdatePlaybackPosition = { id, _ -> gate.await(); positionEpisode(id, 99.0, "2026-11-16T01:02:04.456Z") })
        val commands = commandsFor(api)
        val bRecord = PositionRecord("uB", "p1", 1.0, t0, unsent = false)
        store.seed(bRecord)
        subject = "uA"
        val pending = launch { commands.record("uA", "p1", 10.0) }
        runCurrent()
        assertEquals("Given: R_A は書かれ応答待ち", unsentRecord("uA", "p1", 10.0, t1), store.recordOf("uA", "p1"))

        subject = "uB"
        commands.discardSubject("uA")
        gate.complete(Unit)
        advanceUntilIdle()
        pending.join()

        assertNull("A の記録は復活しない", store.recordOf("uA", "p1"))
        assertEquals("B は変わらない", bRecord, store.recordOf("uB", "p1"))
        assertTrue("旧主体の応答は取り込まれない", store.adoptCalls.isEmpty())
    }

    @Test
    fun `android-possync-29 shared-leave-14 現在の主体が違えば送らない`() = runTest {
        val api = okApi()
        val commands = commandsFor(api)
        store.seed(unsentRecord("uA", "p1", 12.5, t1))
        subject = "uB"

        commands.resendUnsent("uA")

        assertTrue(api.updatePlaybackPositionCalls.isEmpty())
        assertTrue("未送信のまま", store.recordOf("uA", "p1")!!.unsent)
    }

    @Test
    fun `android-possync-31 shared-leave-12 起動時の確定で現在の主体以外の記録を消し前の主体の未送信を送らない`() = runTest {
        val api = okApi()
        val commands = commandsFor(api)
        store.seed(unsentRecord("uA", "pa", 12.5, t1))
        store.seed(unsentRecord("uB", "pb", 3.0, t1))
        subject = "uB"

        commands.onSubjectResolved("uB")
        advanceUntilIdle()

        assertNull("A の記録は消える", store.recordOf("uA", "pa"))
        assertEquals("送信は B の分だけ", listOf("pb" to 3.0), api.updatePlaybackPositionCalls)
        assertFalse(store.recordOf("uB", "pb")!!.unsent)
    }

    @Test
    fun `android-possync-32 shared-leave-12 未認証で確定したら全部の記録を消す`() = runTest {
        val api = okApi()
        val commands = commandsFor(api)
        store.seed(unsentRecord("uA", "pa", 12.5, t1))
        store.seed(unsentRecord("uB", "pb", 3.0, t1))
        subject = null

        commands.onSubjectResolved(null)
        advanceUntilIdle()

        assertTrue(store.all().isEmpty())
        assertTrue(api.updatePlaybackPositionCalls.isEmpty())
    }

    @Test
    fun `android-possync-33 shared-leave-14 再送は記録の主体が現在の主体のときだけ`() = runTest {
        val api = okApi()
        val commands = commandsFor(api)
        store.seed(unsentRecord("uA", "pa", 12.5, t1))
        subject = "uB"

        commands.record("uB", "pb", 3.0)
        advanceUntilIdle()

        assertEquals("A のエピソードは送られない", listOf("pb" to 3.0), api.updatePlaybackPositionCalls)
        assertTrue(store.recordOf("uA", "pa")!!.unsent)
    }

    @Test
    fun `android-possync-34 shared-leave-13 Unknownの間（現在の主体がnull）は回収も再送もしない`() = runTest {
        val api = okApi()
        val commands = commandsFor(api)
        store.seed(unsentRecord("uA", "pa", 12.5, t1))
        subject = null
        val before = store.all()

        commands.resendUnsent(null)
        advanceUntilIdle()

        assertEquals(before, store.all())
        assertTrue(api.updatePlaybackPositionCalls.isEmpty())
    }

    @Test
    fun `android-possync-36 巻き戻した位置も記録して送る（位置の単調増加を要求しない）`() = runTest {
        val api = okApi()
        val commands = commandsFor(api)
        store.seed(PositionRecord("u1", "p1", 30.0, t1, unsent = false))
        clock.now = t2

        commands.record("u1", "p1", 10.0)
        advanceUntilIdle()

        assertEquals(PositionRecord("u1", "p1", 10.0, t2, unsent = false), store.recordOf("u1", "p1"))
        assertEquals(listOf("p1" to 10.0), api.updatePlaybackPositionCalls)
        assertTrue("完聴を撤回しない（markCompleted は呼ばない）", api.markCompletedCalls.isEmpty())
    }

    @Test
    fun `android-possync-38 shared-sync-06 端末の記録が無いepisodeには取り込みで記録を作らない（時刻なしの値でも作らない）`() = runTest {
        val commands = commandsFor(okApi())
        assertTrue("Given: store は空", store.all().isEmpty())

        commands.adoptServerValue("u1", "p1", ServerPosition(90.0, t2))
        commands.adoptServerValue("u1", "p1", ServerPosition(90.0, null))
        commands.adoptEpisodes(
            "u1",
            listOf(positionEpisode("p1", 90.0, "2026-11-16T01:02:04.456Z"), positionEpisode("p2", 50.0, null)),
        )

        assertTrue("記録を作らない", store.all().isEmpty())
        assertTrue("書込が無い", store.writes.isEmpty())
        assertTrue("取り込みは試みられた（vacuous でない）", store.adoptCalls.size >= 4)
    }

    @Test
    fun `android-possync-38 shared-sync-06 主体がnullか現在の主体と違えば取り込みを呼ばない`() = runTest {
        val commands = commandsFor(okApi())
        store.seed(PositionRecord("u1", "p1", 40.0, t1, unsent = false))

        commands.adoptServerValue(null, "p1", ServerPosition(90.0, t2))
        commands.adoptServerValue("uOther", "p1", ServerPosition(90.0, t2))
        commands.adoptEpisodes(null, listOf(positionEpisode("p1", 90.0, "2026-11-16T01:02:04.456Z")))

        assertTrue(store.adoptCalls.isEmpty())
        assertEquals(40.0, store.recordOf("u1", "p1")!!.seconds, 0.0)
    }

    @Test
    fun `android-possync-39 shared-sync-02 主体がnullなら記録も送信もしない`() = runTest {
        val api = okApi()
        val commands = commandsFor(api)

        val recordedAt = commands.record(null, "p1", 12.5)
        advanceUntilIdle()

        assertEquals("時刻だけは返す", t1, recordedAt)
        assertTrue(store.writes.isEmpty())
        assertTrue(api.updatePlaybackPositionCalls.isEmpty())

        // 対照: u1 なら 1 件ずつ起きる。
        commands.record("u1", "p1", 12.5)
        advanceUntilIdle()
        assertEquals(1, store.writes.size)
        assertEquals(1, api.updatePlaybackPositionCalls.size)
    }

    @Test
    fun `android-possync-61 shared-sync-09 記録時刻はミリ秒に切り捨てて保存し送り比べる`() = runTest {
        val api = okApi()
        val commands = commandsFor(api)
        clock.now = Instant.parse("2026-11-16T12:00:00.123456789Z")
        val expected = Instant.parse("2026-11-16T12:00:00.123Z")

        val recordedAt = commands.record("u1", "p1", 12.5)
        advanceUntilIdle()

        assertEquals(expected, recordedAt)
        assertEquals(expected, store.writes.single().recordedAt)
        assertEquals(listOf(expected), api.updatePlaybackPositionRecordedAts)
        assertEquals(expected, store.markSentCalls.single().third)
        assertFalse("切り捨てた同じ値で比べたので送信済みになる", store.recordOf("u1", "p1")!!.unsent)
    }

    @Test
    fun `android-possync-63 shared-leave-11 離脱の後に遅れて走ったrecordは記録も送信も作らない`() = runTest {
        val api = okApi()
        val commands = commandsFor(api)
        subject = "uB"
        commands.discardSubject("uA")

        val recordedAt = commands.record("uA", "p1", 12.5)
        advanceUntilIdle()

        assertEquals(t1, recordedAt)
        assertNull(store.recordOf("uA", "p1"))
        assertTrue(store.writes.isEmpty())
        assertTrue(api.updatePlaybackPositionCalls.isEmpty())

        // 対照: currentSubject が A のままなら記録が 1 件できる。
        subject = "uA"
        commands.record("uA", "p1", 12.5)
        advanceUntilIdle()
        assertEquals(1, store.writes.size)
    }

    @Test
    fun `android-possync-63 shared-leave-11 書込の進行中のdiscardSubjectは書込の後に消し記録を残さない`() = runTest {
        val api = okApi()
        val commands = commandsFor(api)
        val writeGate = CompletableDeferred<Unit>()
        store.writeGate = writeGate
        subject = "uA"
        val recording = launch { commands.record("uA", "p1", 12.5) }
        runCurrent()
        val discarding = launch { commands.discardSubject("uA") }
        runCurrent()
        assertTrue("Given: 書込はまだ終わっていない", store.writes.isEmpty())
        subject = "uB"

        writeGate.complete(Unit)
        advanceUntilIdle()
        recording.join()
        discarding.join()

        assertEquals("書込は先に終わった", 1, store.writes.size)
        assertNull("消された", store.recordOf("uA", "p1"))
        assertTrue("離脱の後は送らない", api.updatePlaybackPositionCalls.isEmpty())
    }

    @Test
    fun `android-possync-66 shared-sync-04 recordは自分の記録の送信だけを待つ`() = runTest {
        val hold = CompletableDeferred<Unit>()
        val api = FakePodcastApi(onUpdatePlaybackPosition = { id, _ -> if (id == "p2") hold.await(); positionEpisode(id) })
        val commands = commandsFor(api)
        store.seed(unsentRecord("u1", "p2", 7.0, t0))

        val recordedAt = commands.record("u1", "p1", 12.5)
        runCurrent()

        assertEquals(t1, recordedAt)
        assertFalse("自分の記録は送信済み", store.recordOf("u1", "p1")!!.unsent)
        assertTrue("他の記録は応答待ちで未送信", store.recordOf("u1", "p2")!!.unsent)
        assertTrue("背景の再送は始まっている（vacuous でない）", api.updatePlaybackPositionCalls.contains("p2" to 7.0))

        hold.complete(Unit)
        advanceUntilIdle()
        assertFalse("応答を返すと送信済みになる（対照）", store.recordOf("u1", "p2")!!.unsent)
    }
}
