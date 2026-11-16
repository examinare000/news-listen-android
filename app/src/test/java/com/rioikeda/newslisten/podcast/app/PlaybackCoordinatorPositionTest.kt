package com.rioikeda.newslisten.podcast.app

import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.network.AudioCacheManager
import com.rioikeda.newslisten.network.FakeFileSystem
import com.rioikeda.newslisten.network.StubNetworkMonitor
import com.rioikeda.newslisten.podcast.FakeEpisodeLookup
import com.rioikeda.newslisten.podcast.FakePlayerController
import com.rioikeda.newslisten.podcast.FakePodcastApiClient
import com.rioikeda.newslisten.podcast.OfflineLibrary
import com.rioikeda.newslisten.podcast.PlaybackSession
import com.rioikeda.newslisten.podcast.PlaybackSession.NothingPlaying
import com.rioikeda.newslisten.podcast.PlaybackState
import com.rioikeda.newslisten.podcast.domain.PositionRecord
import com.rioikeda.newslisten.podcast.domain.ServerPosition
import com.rioikeda.newslisten.preferences.InMemoryPreferencesStore
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlaybackCoordinator] が「いつ」位置を記録するか（4 つの契機・接続の復帰・取り込み・再開位置の候補・離脱との競合）の検証。
 * 保存と送信の「どう」は [PositionCommands] が持ち、ここでは本物の [PositionCommands]・[PositionQueries] を
 * [FakePositionStore]・[FakePodcastApiClient] の上に組んで、Coordinator の入口から観測する。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackCoordinatorPositionTest {

    private val t = Instant.parse("2026-11-16T01:02:03.456Z")
    private val tOld = Instant.parse("2026-11-16T01:02:02.456Z")
    private val tNew = Instant.parse("2026-11-16T01:02:04.456Z")
    private val isoOld = "2026-11-16T01:02:02.456Z"
    private val isoNew = "2026-11-16T01:02:04.456Z"

    private val lookup = FakeEpisodeLookup()
    private val store = FakePositionStore()
    private val clock = TestClock(t)

    private fun ep(id: String, hint: Double = 0.0, at: String? = null, duration: Int = 600): Episode =
        positionEpisode(id, hint, at, duration)

    private suspend fun PlaybackCoordinator.playNow(episode: Episode) {
        lookup.register(episode)
        playNow(episode.id)
    }

    private suspend fun PlaybackCoordinator.addToQueue(episode: Episode) {
        lookup.register(episode)
        addToQueue(episode.id)
    }

    private fun apiFor(
        episodes: List<Episode>,
        events: MutableList<String>? = null,
        onUpdate: suspend (String) -> Unit = {},
    ): FakePodcastApiClient {
        val byId = episodes.associateBy { it.id }
        return FakePodcastApiClient(
            onFetchPodcast = { id -> byId.getValue(id) },
            onUpdatePlaybackPosition = { id, _ ->
                events?.add("position:$id")
                onUpdate(id)
                byId[id] ?: ep(id)
            },
            onMarkCompleted = { id -> events?.add("markCompleted:$id") },
        )
    }

    private class Wired(val coordinator: PlaybackCoordinator, val commands: PositionCommands)

    private fun TestScope.wire(
        apiClient: FakePodcastApiClient,
        player: FakePlayerController,
        networkMonitor: StubNetworkMonitor = StubNetworkMonitor(initialIsOnline = true),
        currentSubject: () -> String? = { "u1" },
    ): Wired {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val commands = PositionCommands(apiClient, store, clock, currentSubject, dispatcher)
        val coordinator = PlaybackCoordinator(
            podcastApi = apiClient,
            playerController = player,
            offlineLibrary = OfflineLibrary(
                AudioCacheManager(FakeFileSystem(), baseDir = "/cache"), apiClient, lookup, currentSubject, dispatcher,
            ),
            episodeLookup = lookup,
            networkMonitor = networkMonitor,
            preferencesStore = InMemoryPreferencesStore(),
            streakQuery = null,
            dispatcher = dispatcher,
            currentSubject = currentSubject,
            positionCommands = commands,
            positionQueries = PositionQueries(store),
        )
        return Wired(coordinator, commands)
    }

    private fun TestScope.emit(player: FakePlayerController, state: PlaybackState) {
        player.setState(state)
        runCurrent()
    }

    private fun TestScope.emitPlaying(player: FakePlayerController) = emit(player, PlaybackState.Playing(0.0, 600.0))

    private fun TestScope.recordSessions(coordinator: PlaybackCoordinator): List<PlaybackSession> {
        val recorded = mutableListOf<PlaybackSession>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { coordinator.session.collect { recorded += it } }
        return recorded
    }

    private fun PlaybackSession.episodeIdOrNull(): String? = when (this) {
        is NothingPlaying -> null
        is PlaybackSession.Starting -> episode.id
        is PlaybackSession.Active -> episode.id
        is PlaybackSession.Completed -> episode.id
        is PlaybackSession.Stopped -> episode.id
        is PlaybackSession.Errored -> ref.id
    }

    // --- 4 つの契機 ---

    @Test
    fun `android-possync-01 shared-sync-01 周期の契機で主体とエピソードごとに最新の位置と時刻を保存する`() = runTest {
        val p1 = ep("p1")
        val apiClient = apiFor(listOf(p1))
        val player = FakePlayerController()
        val coordinator = wire(apiClient, player).coordinator
        coordinator.playNow(p1)
        emitPlaying(player)
        player.setPosition(12.5)
        assertTrue("Given: store は空", store.all().isEmpty())
        assertTrue("Given: 送信 0", apiClient.updatePlaybackPositionCalls.isEmpty())

        advanceTimeBy(15_000)
        runCurrent()

        assertEquals(PositionRecord("u1", "p1", 12.5, t, unsent = false), store.recordOf("u1", "p1"))
        assertEquals(listOf(PositionRecord("u1", "p1", 12.5, t, unsent = true)), store.writes)
        assertEquals(listOf("p1" to 12.5), apiClient.updatePlaybackPositionCalls)
        assertEquals(listOf(t), apiClient.updatePlaybackPositionRecordedAts)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-possync-02 shared-sync-01 一時停止の契機で主体とエピソードごとに最新の位置と時刻を保存する`() = runTest {
        val p1 = ep("p1")
        val apiClient = apiFor(listOf(p1))
        val player = FakePlayerController()
        val coordinator = wire(apiClient, player).coordinator
        coordinator.playNow(p1)
        emitPlaying(player)
        assertTrue("Given: store は空", store.all().isEmpty())

        emit(player, PlaybackState.Paused(12.5, 600.0))

        assertEquals(PositionRecord("u1", "p1", 12.5, t, unsent = false), store.recordOf("u1", "p1"))
        assertEquals(listOf("p1" to 12.5), apiClient.updatePlaybackPositionCalls)
        assertEquals(listOf(t), apiClient.updatePlaybackPositionRecordedAts)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-possync-03 shared-sync-01 切り替えの契機で前のエピソードの最終位置と時刻を保存する`() = runTest {
        val p1 = ep("p1")
        val p2 = ep("p2")
        val apiClient = apiFor(listOf(p1, p2))
        val player = FakePlayerController()
        val coordinator = wire(apiClient, player).coordinator
        coordinator.playNow(p1)
        emitPlaying(player)
        player.setPosition(12.5)
        assertTrue("Given: store は空", store.all().isEmpty())

        coordinator.playNow(p2)

        assertEquals("playNow が戻った時点で送信が済んでいる", listOf("p1" to 12.5), apiClient.updatePlaybackPositionCalls)
        assertEquals(PositionRecord("u1", "p1", 12.5, t, unsent = false), store.recordOf("u1", "p1"))
        assertEquals(listOf(t), apiClient.updatePlaybackPositionRecordedAts)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-possync-04 shared-sync-01 完聴の契機でdurationを位置として時刻と共に保存する`() = runTest {
        val p1 = ep("p1")
        val events = mutableListOf<String>()
        val apiClient = apiFor(listOf(p1), events)
        val player = FakePlayerController()
        val coordinator = wire(apiClient, player).coordinator
        coordinator.playNow(p1)
        emitPlaying(player)
        assertTrue("Given: store は空", store.all().isEmpty())

        emit(player, PlaybackState.Ended(600.0))

        assertEquals(PositionRecord("u1", "p1", 600.0, t, unsent = false), store.recordOf("u1", "p1"))
        assertEquals(listOf("markCompleted:p1", "position:p1"), events)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-possync-60 shared-sync-08 完聴の値は総時間の優先順でv=0なら記録しない`() = runTest {
        // (player の総時間, DTO の duration, 完聴した時点の位置) → 選ばれる v（null は記録しない）
        val cases = listOf(
            Triple(400.0 to 600, 99.0, 400.0),
            Triple(null to 600, 99.0, 600.0),
            Triple(0.0 to 0, 99.0, 99.0),
            Triple(null to 0, 0.0, null),
        )
        for ((durations, position, expected) in cases) {
            val (endedDuration, dtoDuration) = durations
            val id = "c${dtoDuration}_${endedDuration?.toInt()}"
            val episode = ep(id, duration = dtoDuration)
            val events = mutableListOf<String>()
            val apiClient = apiFor(listOf(episode), events)
            val player = FakePlayerController()
            val coordinator = wire(apiClient, player).coordinator
            coordinator.playNow(episode)
            emitPlaying(player)
            player.setPosition(position)
            val label = "ended=$endedDuration dto=$dtoDuration pos=$position"

            emit(player, PlaybackState.Ended(endedDuration))

            assertEquals("$label markCompleted は 1 回", listOf("markCompleted:$id"), events.filter { it.startsWith("markCompleted") })
            if (expected == null) {
                assertTrue("$label 記録も送信もしない", store.recordOf("u1", id) == null)
                assertTrue("$label 送信 0", apiClient.updatePlaybackPositionCalls.isEmpty())
            } else {
                assertEquals("$label 記録", expected, store.recordOf("u1", id)!!.seconds, 0.0)
                assertEquals("$label 送信", listOf(id to expected), apiClient.updatePlaybackPositionCalls)
            }
            coordinator.stopForSubjectLeave()
        }
    }

    @Test
    fun `android-possync-60 shared-sync-08 完聴の位置は未送信で記録され再送の対象になる`() = runTest {
        val p1 = ep("p1")
        val events = mutableListOf<String>()
        var failing = true
        val apiClient = apiFor(
            listOf(p1), events,
            onUpdate = { if (failing) throw ApiException.NetworkError(IOException("offline")) },
        )
        val player = FakePlayerController()
        val monitor = StubNetworkMonitor(initialIsOnline = true)
        val coordinator = wire(apiClient, player, monitor).coordinator
        coordinator.playNow(p1)
        emitPlaying(player)

        emit(player, PlaybackState.Ended(600.0))
        monitor.setOnline(false)
        runCurrent()

        assertTrue("送信に失敗した完聴の位置は未送信で残る", store.recordOf("u1", "p1")!!.unsent)

        failing = false
        monitor.setOnline(true)
        runCurrent()

        assertFalse("接続の復帰で再送して送信済みになる", store.recordOf("u1", "p1")!!.unsent)
        assertEquals("markCompleted は再送しない", 1, events.count { it.startsWith("markCompleted") })
        assertEquals("位置は失敗の 1 回と再送の 1 回", 2, events.count { it == "position:p1" })

        coordinator.stopForSubjectLeave()
    }

    // --- 接続の復帰 ---

    @Test
    fun `android-possync-14 shared-sync-03 isOnlineのfalseからtrueで現在の主体の未送信を再送する（trueのままは再送しない）`() = runTest {
        val p1 = ep("p1")
        val apiClient = apiFor(listOf(p1))
        val player = FakePlayerController()
        val monitor = StubNetworkMonitor(initialIsOnline = true)
        val coordinator = wire(apiClient, player, monitor).coordinator
        coordinator.playNow(p1)
        emitPlaying(player)
        store.seed(PositionRecord("u1", "x1", 7.0, tOld, unsent = true))
        runCurrent()
        assertTrue("最初の値が true のときは再送しない", apiClient.updatePlaybackPositionCalls.isEmpty())

        monitor.setOnline(false)
        runCurrent()
        assertTrue("true → false では再送しない", apiClient.updatePlaybackPositionCalls.isEmpty())

        monitor.setOnline(true)
        runCurrent()
        assertEquals(listOf("x1" to 7.0), apiClient.updatePlaybackPositionCalls)
        assertEquals("同じ記録時刻のまま", listOf(tOld), apiClient.updatePlaybackPositionRecordedAts)
        assertFalse(store.recordOf("u1", "x1")!!.unsent)

        store.seed(PositionRecord("u1", "x2", 8.0, tOld, unsent = true))
        monitor.setOnline(true)
        runCurrent()
        assertEquals("true のまま流しても増えない", 1, apiClient.updatePlaybackPositionCalls.size)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-possync-15 shared-sync-03 セッションがidleでも再送は送る`() = runTest {
        val apiClient = apiFor(emptyList())
        val monitor = StubNetworkMonitor(initialIsOnline = false)
        val coordinator = wire(apiClient, FakePlayerController(), monitor).coordinator
        store.seed(PositionRecord("u1", "x1", 7.0, tOld, unsent = true))
        runCurrent()
        assertEquals("Given: 何も再生していない", NothingPlaying, coordinator.session.value)
        assertTrue(apiClient.updatePlaybackPositionCalls.isEmpty())

        monitor.setOnline(true)
        runCurrent()

        assertEquals(listOf("x1" to 7.0), apiClient.updatePlaybackPositionCalls)
        assertEquals(NothingPlaying, coordinator.session.value)
    }

    @Test
    fun `android-possync-16 shared-sync-03 主体が未確定の間は再送しない`() = runTest {
        val apiClient = apiFor(emptyList())
        val monitor = StubNetworkMonitor(initialIsOnline = false)
        wire(apiClient, FakePlayerController(), monitor, currentSubject = { null })
        store.seed(PositionRecord("u1", "x1", 7.0, tOld, unsent = true))
        runCurrent()

        monitor.setOnline(true)
        runCurrent()

        assertTrue(apiClient.updatePlaybackPositionCalls.isEmpty())
        assertTrue(store.recordOf("u1", "x1")!!.unsent)
    }

    // --- 取り込みはセッションを動かさない ---

    @Test
    fun `android-possync-19 shared-sync-06 取り込みは再生中と一時停止中のセッションの位置を動かさない`() = runTest {
        val p1 = ep("p1")
        val apiClient = apiFor(listOf(p1))
        val player = FakePlayerController()
        val wired = wire(apiClient, player)
        val coordinator = wired.coordinator
        coordinator.playNow(p1)
        emitPlaying(player)
        player.setPosition(40.0)
        emit(player, PlaybackState.Paused(40.0, 600.0))
        assertEquals("Given: 端末の記録 (40, t)", 40.0, store.recordOf("u1", "p1")!!.seconds, 0.0)
        val sessionBefore = coordinator.session.value
        val seeksBefore = player.seekCalls.toList()

        wired.commands.adoptServerValue("u1", "p1", ServerPosition(90.0, tNew))
        runCurrent()

        assertEquals("store は 90", 90.0, store.recordOf("u1", "p1")!!.seconds, 0.0)
        assertEquals("player は 40", 40.0, player.positionSeconds.value, 0.0)
        assertEquals(seeksBefore, player.seekCalls.toList())
        assertEquals(sessionBefore, coordinator.session.value)

        // 再生中も同じ。
        emitPlaying(player)
        val playingSession = coordinator.session.value
        wired.commands.adoptServerValue("u1", "p1", ServerPosition(95.0, Instant.parse("2026-11-16T01:02:05.456Z")))
        runCurrent()
        assertEquals(95.0, store.recordOf("u1", "p1")!!.seconds, 0.0)
        assertEquals(40.0, player.positionSeconds.value, 0.0)
        assertEquals(seeksBefore, player.seekCalls.toList())
        assertEquals(playingSession, coordinator.session.value)

        coordinator.stopForSubjectLeave()
    }

    // --- 再開位置の候補 ---

    @Test
    fun `android-possync-24 shared-resume-08 開始の再開位置は端末の記録とサーバーの値の新しい方から末尾2秒窓を通す`() = runTest {
        store.seed(PositionRecord("u1", "p1", 300.0, tNew, unsent = false))
        val apiClient = apiFor(listOf(ep("p1", hint = 10.0, at = isoOld)))
        val player = FakePlayerController()
        val coordinator = wire(apiClient, player).coordinator

        coordinator.playNow(ep("p1", hint = 10.0, at = isoOld))

        assertEquals("端末の記録が新しい: 300 から始める", listOf(300.0), player.seekCalls)
        coordinator.stopForSubjectLeave()

        // 末尾 2 秒窓: 候補 598 / duration 600 は 0 から始める。
        store.seed(PositionRecord("u1", "p9", 598.0, tNew, unsent = false))
        val p9 = ep("p9", hint = 0.0, at = null, duration = 600)
        val player2 = FakePlayerController()
        val coordinator2 = wire(apiFor(listOf(p9)), player2).coordinator
        coordinator2.playNow(p9)
        assertEquals(1, player2.prepareCalls.size)
        assertTrue("窓の中は先頭から始める: ${player2.seekCalls}", player2.seekCalls.isEmpty())
        coordinator2.stopForSubjectLeave()
    }

    @Test
    fun `android-possync-25 候補の読みの間に離脱が起きたらplayerを始めない`() = runTest {
        val p1 = ep("p1", hint = 10.0, at = isoOld)
        store.seed(PositionRecord("u1", "p1", 300.0, tNew, unsent = false))
        val apiClient = apiFor(listOf(p1))
        val player = FakePlayerController()
        val coordinator = wire(apiClient, player).coordinator
        val gate = CompletableDeferred<Unit>()
        store.readGate = gate

        launch { coordinator.playNow(p1) }
        runCurrent()
        assertEquals("Given: 候補の読みで止まっている", 1, store.readCalls)
        coordinator.stopForSubjectLeave()
        gate.complete(Unit)
        runCurrent()

        assertTrue(player.prepareCalls.isEmpty())
        assertEquals(NothingPlaying, coordinator.session.value)
    }

    // --- 離脱の停止 ---

    @Test
    fun `android-possync-30 shared-leave-11 離脱の停止は記録も送信もしない`() = runTest {
        val p1 = ep("p1")
        val apiClient = apiFor(listOf(p1))
        val player = FakePlayerController()
        val coordinator = wire(apiClient, player).coordinator
        coordinator.playNow(p1)
        emitPlaying(player)
        player.setPosition(12.5)
        assertTrue("Given: store は空", store.all().isEmpty())

        coordinator.stopForSubjectLeave()
        advanceTimeBy(30_000)
        runCurrent()

        assertTrue(store.all().isEmpty())
        assertTrue(store.writes.isEmpty())
        assertTrue(apiClient.updatePlaybackPositionCalls.isEmpty())
    }

    // --- 取り込みと利用者の開始・離脱の競合 ---

    @Test
    fun `android-possync-64 取り込みの最中の離脱でキューを書かない`() = runTest {
        val p1 = ep("p1")
        val apiClient = apiFor(listOf(p1))
        val player = FakePlayerController()
        val coordinator = wire(apiClient, player).coordinator
        val gate = CompletableDeferred<Unit>()
        store.adoptGate = gate

        launch { coordinator.playNow(p1) }
        runCurrent()
        assertEquals("Given: 取り込みで止まっている", 1, store.adoptCalls.size)
        coordinator.stopForSubjectLeave()
        gate.complete(Unit)
        runCurrent()

        assertEquals(null, coordinator.queue.value.current)
        assertEquals(NothingPlaying, coordinator.session.value)
        assertTrue(player.prepareCalls.isEmpty())
    }

    @Test
    fun `android-possync-64 取り込みの最中に利用者がbを開始すると自動の側は何も書かない`() = runTest {
        val a = ep("pA")
        val b = ep("pB")
        val c = ep("pC")
        val apiClient = apiFor(listOf(a, b, c))
        val player = FakePlayerController()
        val coordinator = wire(apiClient, player).coordinator
        coordinator.playNow(a)
        coordinator.addToQueue(c)
        emitPlaying(player)
        val sessions = recordSessions(coordinator)
        val gate = CompletableDeferred<Unit>()
        store.adoptGate = gate
        val adoptsBefore = store.adoptCalls.size

        emit(player, PlaybackState.Ended(600.0)) // 自動の c の開始が取り込みで止まる
        assertEquals("Given: c の取り込みで止まっている", adoptsBefore + 1, store.adoptCalls.size)
        lookup.register(b)
        launch { coordinator.playNow("pB") }
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf(a.audioUrlOf(), b.audioUrlOf()), player.prepareCalls)
        assertTrue("自動の側の遷移が現れた: $sessions", sessions.none { it.episodeIdOrNull() == "pC" })
        assertEquals("pB", coordinator.session.value.episodeIdOrNull())

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-possync-64 shared-session-17 候補の読みの間に利用者が開始すると自動のcは始まらずキューに残る`() = runTest {
        val a = ep("pA")
        val b = ep("pB")
        val c = ep("pC")
        val apiClient = apiFor(listOf(a, b, c))
        val player = FakePlayerController()
        val coordinator = wire(apiClient, player).coordinator
        coordinator.playNow(a)
        coordinator.addToQueue(c)
        emitPlaying(player)
        val sessions = recordSessions(coordinator)
        val gate = CompletableDeferred<Unit>()
        store.readGate = gate
        val readsBefore = store.readCalls

        emit(player, PlaybackState.Ended(600.0)) // 自動の c の開始が候補の読みで止まる
        assertEquals("Given: c の候補の読みで止まっている", readsBefore + 1, store.readCalls)
        lookup.register(b)
        launch { coordinator.playNow("pB") }
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertTrue("自動の側の遷移が現れた: $sessions", sessions.none { it.episodeIdOrNull() == "pC" })
        assertEquals("prepare に c が無い", listOf(a.audioUrlOf(), b.audioUrlOf()), player.prepareCalls)
        assertEquals("pB", coordinator.session.value.episodeIdOrNull())
        assertTrue(coordinator.session.value is PlaybackSession.Starting)
        assertEquals("pB", coordinator.queue.value.current?.id)
        assertEquals(listOf("pC"), coordinator.queue.value.upNext.map { it.id })

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-possync-64 shared-session-17 候補の読みの間に利用者の開始が無ければ自動のcが始まる（対照）`() = runTest {
        val a = ep("pA")
        val c = ep("pC")
        val apiClient = apiFor(listOf(a, c))
        val player = FakePlayerController()
        val coordinator = wire(apiClient, player).coordinator
        coordinator.playNow(a)
        coordinator.addToQueue(c)
        emitPlaying(player)
        val gate = CompletableDeferred<Unit>()
        store.readGate = gate

        emit(player, PlaybackState.Ended(600.0))
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf(a.audioUrlOf(), c.audioUrlOf()), player.prepareCalls)
        assertEquals("pC", coordinator.session.value.episodeIdOrNull())

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-possync-65 shared-sync-03 遅い再送の間も次の開始が進む`() = runTest {
        val a = ep("pA")
        val b = ep("pB")
        val hold = CompletableDeferred<Unit>()
        val apiClient = apiFor(listOf(a, b), onUpdate = { id -> if (id.startsWith("x")) hold.await() })
        val player = FakePlayerController()
        val coordinator = wire(apiClient, player).coordinator
        store.seed(PositionRecord("u1", "x1", 1.0, tOld, unsent = true))
        store.seed(PositionRecord("u1", "x2", 2.0, tOld, unsent = true))
        coordinator.playNow(a)
        emitPlaying(player)
        player.setPosition(12.5)

        coordinator.playNow(b)
        runCurrent()

        assertEquals(listOf(a.audioUrlOf(), b.audioUrlOf()), player.prepareCalls)
        assertEquals("A の最終位置は記録され、送信は 1 回済み", PositionRecord("u1", "pA", 12.5, t, unsent = false), store.recordOf("u1", "pA"))
        assertEquals(1, apiClient.updatePlaybackPositionCalls.count { it.first == "pA" })
        assertTrue("再送は応答待ちで未送信", store.recordOf("u1", "x1")!!.unsent)

        hold.complete(Unit)
        runCurrent()
        assertFalse(store.recordOf("u1", "x1")!!.unsent)
        assertFalse(store.recordOf("u1", "x2")!!.unsent)

        coordinator.stopForSubjectLeave()
    }

    private fun Episode.audioUrlOf(): String = (this as Episode.Playable).audioUrl
}
