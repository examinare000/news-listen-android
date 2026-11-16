package com.rioikeda.newslisten.podcast.app

import com.rioikeda.newslisten.network.AudioCacheManager
import com.rioikeda.newslisten.network.FakeFileSystem
import com.rioikeda.newslisten.network.StubNetworkMonitor
import com.rioikeda.newslisten.podcast.FakeEpisodeLookup
import com.rioikeda.newslisten.podcast.FakePlayerController
import com.rioikeda.newslisten.podcast.FakePodcastApiClient
import com.rioikeda.newslisten.podcast.domain.PlaybackFailureReason
import com.rioikeda.newslisten.podcast.domain.PlaybackSession
import com.rioikeda.newslisten.podcast.domain.PlaybackState
import com.rioikeda.newslisten.podcast.domain.PositionRecord
import com.rioikeda.newslisten.podcast.domain.ResumeChoice
import com.rioikeda.newslisten.preferences.InMemoryPreferencesStore
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画面の再生ボタンで再開するときの確認（別の端末の新しい位置を尋ねる。shared-sync-10〜15・android-dec-124〜128）の検証。
 * 確認を出す・出さない・消す条件と、「この端末の最後の記録の時刻」の印の単調さを、Coordinator の入口から観測する。
 * 本物の [PositionCommands]・[PositionQueries] を [FakePositionStore]・[FakePodcastApiClient] の上に組む。
 *
 * 共通の Given: 主体 u1 が p1（600 秒）を再生し、位置 30 秒で一時停止した（一時停止の記録の時刻は [tP]）。
 * その後、別の端末の記録として p1 の位置が [FakePositionStore.seed] で置かれる。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackCoordinatorResumePromptTest {

    /** 一時停止の記録の時刻。 */
    private val tP = Instant.parse("2026-11-16T01:02:03.456Z")
    private val tS = tP.plusSeconds(10)

    private val p1 = positionEpisode("p1", durationSeconds = 600)
    private val p2 = positionEpisode("p2", durationSeconds = 600)
    private val lookup = FakeEpisodeLookup().also {
        it.register(p1)
        it.register(p2)
    }
    private val store = FakePositionStore()
    private val clock = TestClock(tP)

    /** 非 null の間、episode の取得はこの完了を待つ。 */
    private var fetchGate: CompletableDeferred<Unit>? = null

    /** 非 null の間、次の 1 回の位置の送信だけがこの完了を待つ（待ち始めたら null に戻す）。 */
    private var updateGate: CompletableDeferred<Unit>? = null

    private fun apiClient(): FakePodcastApiClient {
        val byId = listOf(p1, p2).associateBy { it.id }
        return FakePodcastApiClient(
            onFetchPodcast = { id ->
                fetchGate?.await()
                byId.getValue(id)
            },
            onUpdatePlaybackPosition = { id, _ ->
                val gate = updateGate
                updateGate = null
                gate?.await()
                byId.getValue(id)
            },
        )
    }

    private fun TestScope.wire(apiClient: FakePodcastApiClient, player: FakePlayerController): PlaybackCoordinator {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val currentSubject: () -> String? = { "u1" }
        return PlaybackCoordinator(
            podcastApi = apiClient,
            playerController = player,
            offlineLibrary = OfflineLibrary(
                AudioCacheManager(FakeFileSystem(), baseDir = "/cache"), apiClient, lookup, currentSubject, dispatcher,
            ),
            episodeLookup = lookup,
            networkMonitor = StubNetworkMonitor(initialIsOnline = true),
            preferencesStore = InMemoryPreferencesStore(),
            streakQuery = null,
            dispatcher = dispatcher,
            currentSubject = currentSubject,
            positionCommands = PositionCommands(apiClient, store, clock, currentSubject, dispatcher),
            positionQueries = PositionQueries(store),
        )
    }

    private fun TestScope.emit(player: FakePlayerController, state: PlaybackState) {
        player.setState(state)
        runCurrent()
    }

    private fun otherDevice(seconds: Double, at: Instant) = PositionRecord("u1", "p1", seconds, at, unsent = false)

    private class Scene(
        val coordinator: PlaybackCoordinator,
        val player: FakePlayerController,
        val apiClient: FakePodcastApiClient,
        /** 共通の Given が終わった時点の `play()` の回数（`playNow` の 1 回）。 */
        val playCountBefore: Int,
    )

    /** 共通の Given。時計は [tS] へ進み、別の端末の記録（[otherSeconds]・[otherAt]）が置かれている。確認はまだ出していない。 */
    private suspend fun TestScope.givenPausedWithOtherDeviceRecord(otherSeconds: Double = 90.0, otherAt: Instant = tS): Scene {
        val apiClient = apiClient()
        val player = FakePlayerController()
        val coordinator = wire(apiClient, player)
        coordinator.playNow("p1")
        emit(player, PlaybackState.Playing(0.0, 600.0))
        player.setPosition(30.0)
        emit(player, PlaybackState.Paused(30.0, 600.0))
        clock.now = tS
        store.seed(otherDevice(otherSeconds, otherAt))
        return Scene(coordinator, player, apiClient, player.playCallCount)
    }

    /** 確認を出させ、確認が出た状態から始めるテストの前提として、出ていることを確かめる。 */
    private suspend fun Scene.showPrompt(otherSeconds: Double = 90.0) {
        coordinator.togglePlayPause()
        assertEquals(ResumePrompt("p1", 30.0, otherSeconds), coordinator.resumePrompt.value)
    }

    // --- 確認を出す・出さない ---

    @Test
    fun `android-possync-40 shared-sync-10 一時停止中にサーバーの記録が新しく差が15秒以上なら再生せず確認を出す`() = runTest {
        val s = givenPausedWithOtherDeviceRecord()

        s.coordinator.togglePlayPause()

        assertEquals(ResumePrompt("p1", 30.0, 90.0), s.coordinator.resumePrompt.value)
        assertEquals("再生していない", s.playCountBefore, s.player.playCallCount)
        assertTrue("位置を動かしていない", s.player.seekCalls.isEmpty())
    }

    @Test
    fun `android-possync-41 shared-sync-10 差がちょうど15秒でも確認を出す`() = runTest {
        val s = givenPausedWithOtherDeviceRecord(otherSeconds = 45.0)

        s.coordinator.togglePlayPause()

        assertEquals(ResumePrompt("p1", 30.0, 45.0), s.coordinator.resumePrompt.value)
        assertEquals(s.playCountBefore, s.player.playCallCount)
    }

    @Test
    fun `android-possync-42 shared-sync-10 差が15秒未満なら確認せずこの端末の位置から再生する`() = runTest {
        val s = givenPausedWithOtherDeviceRecord(otherSeconds = 44.999)

        s.coordinator.togglePlayPause()

        assertNull(s.coordinator.resumePrompt.value)
        assertEquals(s.playCountBefore + 1, s.player.playCallCount)
        assertTrue("位置を動かしていない", s.player.seekCalls.isEmpty())
    }

    @Test
    fun `android-possync-43 shared-sync-10 サーバーの方が同じ時刻なら確認しない`() = runTest {
        val s = givenPausedWithOtherDeviceRecord(otherSeconds = 90.0, otherAt = tP)

        s.coordinator.togglePlayPause()

        assertNull(s.coordinator.resumePrompt.value)
        assertEquals(s.playCountBefore + 1, s.player.playCallCount)
    }

    @Test
    fun `android-possync-43 shared-sync-10 サーバーの方が古い時刻なら確認しない`() = runTest {
        val s = givenPausedWithOtherDeviceRecord(otherSeconds = 90.0, otherAt = tP.minusSeconds(1))

        s.coordinator.togglePlayPause()

        assertNull(s.coordinator.resumePrompt.value)
        assertEquals(s.playCountBefore + 1, s.player.playCallCount)
    }

    @Test
    fun `android-possync-47 shared-sync-10 再生中の再生ボタンは一時停止で確認を出さない`() = runTest {
        val s = givenPausedWithOtherDeviceRecord()
        emit(s.player, PlaybackState.Playing(30.0, 600.0))
        val pausesBefore = s.player.pauseCallCount

        s.coordinator.togglePlayPause()

        assertNull(s.coordinator.resumePrompt.value)
        assertEquals(pausesBefore + 1, s.player.pauseCallCount)
        assertEquals("再生はしない", s.playCountBefore, s.player.playCallCount)

        s.coordinator.stopForSubjectLeave()
    }

    // --- Active 以外・開始の処理中 ---

    @Test
    fun `android-possync-48 shared-sync-10 Startingでは確認しない`() = runTest {
        val apiClient = apiClient()
        val player = FakePlayerController()
        val coordinator = wire(apiClient, player)
        coordinator.playNow("p1")
        store.seed(otherDevice(90.0, tS))
        assertTrue("Given: Starting", coordinator.session.value is PlaybackSession.Starting)
        val playsBefore = player.playCallCount

        coordinator.togglePlayPause()

        assertNull(coordinator.resumePrompt.value)
        assertEquals(playsBefore + 1, player.playCallCount)
    }

    @Test
    fun `android-possync-48 shared-sync-10 Erroredでは確認しない`() = runTest {
        val s = givenPausedWithOtherDeviceRecord()
        emit(s.player, PlaybackState.Failed(PlaybackFailureReason.Source))
        assertTrue("Given: Errored", s.coordinator.session.value is PlaybackSession.Errored)

        s.coordinator.togglePlayPause()

        assertNull(s.coordinator.resumePrompt.value)
        assertEquals(s.playCountBefore + 1, s.player.playCallCount)
    }

    @Test
    fun `android-possync-48 shared-sync-10 離脱の後のNothingPlayingでは確認しない`() = runTest {
        val s = givenPausedWithOtherDeviceRecord()
        s.coordinator.stopForSubjectLeave()
        store.seed(otherDevice(90.0, tS.plusSeconds(1)))
        assertTrue("Given: NothingPlaying", s.coordinator.session.value is PlaybackSession.NothingPlaying)

        s.coordinator.togglePlayPause()

        assertNull(s.coordinator.resumePrompt.value)
        assertEquals(s.playCountBefore + 1, s.player.playCallCount)
    }

    @Test
    fun `android-possync-48 shared-sync-10 キューが尽きた後のStoppedでは確認しない`() = runTest {
        val s = givenPausedWithOtherDeviceRecord()
        emit(s.player, PlaybackState.Ended(600.0))
        advanceUntilIdle()
        store.seed(otherDevice(90.0, tS.plusSeconds(1)))
        assertTrue("Given: Stopped", s.coordinator.session.value is PlaybackSession.Stopped)

        s.coordinator.togglePlayPause()

        assertNull(s.coordinator.resumePrompt.value)
        assertEquals(s.playCountBefore + 1, s.player.playCallCount)
    }

    @Test
    fun `android-possync-48 shared-sync-10 キューが尽きた後にplayerが一時停止しても確認しない`() = runTest {
        val s = givenPausedWithOtherDeviceRecord()
        emit(s.player, PlaybackState.Ended(600.0))
        advanceUntilIdle()
        clock.now = tS.plusSeconds(10)
        store.seed(otherDevice(90.0, tS.plusSeconds(10)))
        emit(s.player, PlaybackState.Paused(0.0, 600.0))
        assertTrue("Given: Stopped のまま player だけが Paused", s.coordinator.session.value is PlaybackSession.Stopped)

        s.coordinator.togglePlayPause()

        assertNull(s.coordinator.resumePrompt.value)
        assertEquals(s.playCountBefore + 1, s.player.playCallCount)
    }

    @Test
    fun `android-possync-48 shared-sync-10 開始の処理中は確認しない`() = runTest {
        val s = givenPausedWithOtherDeviceRecord()
        val gate = CompletableDeferred<Unit>()
        fetchGate = gate
        launch { s.coordinator.playNow("p2") }
        runCurrent()
        assertTrue("Given: 開始の処理中も session は p1 の Active", s.coordinator.session.value is PlaybackSession.Active)

        s.coordinator.togglePlayPause()

        assertNull(s.coordinator.resumePrompt.value)
        assertEquals(s.playCountBefore + 1, s.player.playCallCount)

        gate.complete(Unit)
        runCurrent()
    }

    // --- 確認の後の選択 ---

    @Test
    fun `android-possync-46 shared-sync-12 閉じたら一時停止のままで再生せず次の再生ボタンでまた尋ねる`() = runTest {
        val s = givenPausedWithOtherDeviceRecord()
        s.showPrompt()
        val writesBefore = store.writes.size

        s.coordinator.dismissResumePrompt()

        assertNull(s.coordinator.resumePrompt.value)
        assertEquals(s.playCountBefore, s.player.playCallCount)
        assertTrue(s.player.seekCalls.isEmpty())
        assertEquals("記録を作らない", writesBefore, store.writes.size)

        s.coordinator.togglePlayPause()

        assertEquals(ResumePrompt("p1", 30.0, 90.0), s.coordinator.resumePrompt.value)
        assertEquals(s.playCountBefore, s.player.playCallCount)
    }

    @Test
    fun `android-possync-44 shared-sync-12 別の端末の位置からを選ぶとその位置へ移って再生する`() = runTest {
        val s = givenPausedWithOtherDeviceRecord()
        s.showPrompt()

        s.coordinator.choosePosition(ResumeChoice.OtherDevice)

        assertNull(s.coordinator.resumePrompt.value)
        assertEquals(listOf(90.0), s.player.seekCalls)
        assertEquals(s.playCountBefore + 1, s.player.playCallCount)
    }

    @Test
    fun `android-possync-44 shared-sync-12 末尾2秒窓の位置は先頭から再生する`() = runTest {
        val s = givenPausedWithOtherDeviceRecord(otherSeconds = 599.0)
        s.showPrompt(otherSeconds = 599.0)

        s.coordinator.choosePosition(ResumeChoice.OtherDevice)

        assertNull(s.coordinator.resumePrompt.value)
        assertEquals(listOf(0.0), s.player.seekCalls)
        assertEquals(s.playCountBefore + 1, s.player.playCallCount)
    }

    @Test
    fun `android-possync-44 shared-sync-12 選んだ後の2回目の選択は何もしない`() = runTest {
        val s = givenPausedWithOtherDeviceRecord()
        s.showPrompt()
        s.coordinator.choosePosition(ResumeChoice.OtherDevice)

        s.coordinator.choosePosition(ResumeChoice.OtherDevice)

        assertEquals("seek は 1 回だけ", listOf(90.0), s.player.seekCalls)
        assertEquals("play も 1 回だけ", s.playCountBefore + 1, s.player.playCallCount)
    }

    @Test
    fun `android-possync-45 shared-sync-13 この端末の位置からを選ぶと移動せず再生し今の位置を今の時刻で記録して送る`() = runTest {
        val s = givenPausedWithOtherDeviceRecord()
        s.showPrompt()
        val tN = tP.plusSeconds(20)
        clock.now = tN

        s.coordinator.choosePosition(ResumeChoice.ThisDevice)
        runCurrent()

        assertNull(s.coordinator.resumePrompt.value)
        assertTrue("位置を動かしていない", s.player.seekCalls.isEmpty())
        assertEquals(s.playCountBefore + 1, s.player.playCallCount)
        assertEquals(PositionRecord("u1", "p1", 30.0, tN, unsent = true), store.writes.last())
        assertEquals("p1" to 30.0, s.apiClient.updatePlaybackPositionCalls.last())
        assertEquals(tN, s.apiClient.updatePlaybackPositionRecordedAts.last())

        // 印が更新されたことの観測: tP < tM < tN の別の端末の記録は、この端末の最後の記録（tN）より古いので尋ねない。
        store.seed(otherDevice(90.0, tP.plusSeconds(15)))
        s.coordinator.togglePlayPause()

        assertNull(s.coordinator.resumePrompt.value)
        assertEquals(s.playCountBefore + 2, s.player.playCallCount)
    }

    @Test
    fun `android-possync-45 shared-sync-13 この端末の位置からの記録の送信を待たずに再生する`() = runTest {
        val s = givenPausedWithOtherDeviceRecord()
        s.showPrompt()
        val gate = CompletableDeferred<Unit>()
        updateGate = gate

        val choosing = launch { s.coordinator.choosePosition(ResumeChoice.ThisDevice) }
        runCurrent()

        assertEquals("送信が終わる前に再生が始まっている", s.playCountBefore + 1, s.player.playCallCount)

        gate.complete(Unit)
        runCurrent()
        choosing.join()
    }

    // --- 確認を消す条件 ---

    @Test
    fun `android-possync-49 shared-sync-10 新しい開始の要求の時点で確認が消え後から選んでも何もしない`() = runTest {
        val s = givenPausedWithOtherDeviceRecord()
        s.showPrompt()
        val writesBefore = store.writes.size
        val gate = CompletableDeferred<Unit>()
        fetchGate = gate

        launch { s.coordinator.playNow("p2") }
        runCurrent()

        assertNull("取得が終わる前に確認が消えている", s.coordinator.resumePrompt.value)
        s.coordinator.choosePosition(ResumeChoice.OtherDevice)
        s.coordinator.choosePosition(ResumeChoice.ThisDevice)
        assertTrue("seek しない", s.player.seekCalls.isEmpty())
        assertEquals("play しない", s.playCountBefore, s.player.playCallCount)
        assertEquals("記録しない", writesBefore, store.writes.size)

        gate.complete(Unit)
        runCurrent()
    }

    @Test
    fun `android-possync-49 shared-sync-10 離脱で確認が消える`() = runTest {
        val s = givenPausedWithOtherDeviceRecord()
        s.showPrompt()

        s.coordinator.stopForSubjectLeave()

        assertNull(s.coordinator.resumePrompt.value)
        s.coordinator.choosePosition(ResumeChoice.OtherDevice)
        assertTrue("消えた確認から seek しない", s.player.seekCalls.isEmpty())
    }

    @Test
    fun `android-possync-49 shared-sync-10 完聴で確認が消える`() = runTest {
        val s = givenPausedWithOtherDeviceRecord()
        s.showPrompt()

        emit(s.player, PlaybackState.Ended(600.0))

        assertNull(s.coordinator.resumePrompt.value)
    }

    @Test
    fun `android-possync-49 shared-sync-10 失敗で確認が消える`() = runTest {
        val s = givenPausedWithOtherDeviceRecord()
        s.showPrompt()

        emit(s.player, PlaybackState.Failed(PlaybackFailureReason.Source))

        assertNull(s.coordinator.resumePrompt.value)
    }

    @Test
    fun `android-possync-54 shared-sync-15 確認の表示中にplayerが再生を始めたら確認が消え位置を動かさない`() = runTest {
        val s = givenPausedWithOtherDeviceRecord()
        s.showPrompt()

        emit(s.player, PlaybackState.Playing(30.0, 600.0))

        assertNull(s.coordinator.resumePrompt.value)
        s.coordinator.choosePosition(ResumeChoice.OtherDevice)
        assertTrue("消えた確認から seek しない", s.player.seekCalls.isEmpty())
        assertEquals(s.playCountBefore, s.player.playCallCount)

        s.coordinator.stopForSubjectLeave()
    }

    // --- 画面のボタン以外の入口・再起動 ---

    @Test
    fun `android-possync-51 shared-sync-11 playerへの直接のplay（MediaSession経由の代理）では確認を出さず端末の位置のまま再開する`() = runTest {
        val s = givenPausedWithOtherDeviceRecord()
        val writesBefore = store.writes.size

        emit(s.player, PlaybackState.Playing(30.0, 600.0))

        assertNull(s.coordinator.resumePrompt.value)
        assertTrue("位置を動かしていない", s.player.seekCalls.isEmpty())
        assertEquals("Coordinator は play() を呼んでいない", s.playCountBefore, s.player.playCallCount)
        assertEquals(30.0, s.player.positionSeconds.value, 0.0)

        advanceTimeBy(15_000)
        runCurrent()

        assertEquals("周期の記録が始まる", writesBefore + 1, store.writes.size)
        assertEquals(30.0, store.writes.last().seconds, 0.0)

        s.coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-possync-52 shared-sync-11 再起動の後は端末の記録がサーバー由来でも尋ねない`() = runTest {
        store.seed(otherDevice(90.0, tS))
        val apiClient = apiClient()
        val player = FakePlayerController()
        val restarted = wire(apiClient, player)

        restarted.togglePlayPause()

        assertNull(restarted.resumePrompt.value)
        assertEquals(1, player.playCallCount)

        restarted.playNow("p1")

        assertEquals("再開位置は端末の記録の候補どおり", listOf(90.0), player.seekCalls)
    }

    // --- 読みの間の競合 ---

    @Test
    fun `android-possync-53 shared-sync-10 端末の記録の読みの間に離脱が起きたら確認も再生もしない`() = runTest {
        val s = givenPausedWithOtherDeviceRecord()
        val gate = CompletableDeferred<Unit>()
        val readsBefore = store.readCalls
        store.readGate = gate
        launch { s.coordinator.togglePlayPause() }
        runCurrent()
        assertEquals("Given: 端末の記録の読みで止まっている", readsBefore + 1, store.readCalls)

        s.coordinator.stopForSubjectLeave()
        gate.complete(Unit)
        runCurrent()

        assertNull(s.coordinator.resumePrompt.value)
        assertEquals(s.playCountBefore, s.player.playCallCount)
    }

    @Test
    fun `android-possync-53 shared-sync-10 端末の記録の読みの間に新しい開始が起きたら確認も再生もしない`() = runTest {
        val s = givenPausedWithOtherDeviceRecord()
        val gate = CompletableDeferred<Unit>()
        val readsBefore = store.readCalls
        store.readGate = gate
        launch { s.coordinator.togglePlayPause() }
        runCurrent()
        assertEquals("Given: 端末の記録の読みで止まっている", readsBefore + 1, store.readCalls)
        launch { s.coordinator.playNow("p2") }
        runCurrent()

        gate.complete(Unit)
        runCurrent()

        assertNull(s.coordinator.resumePrompt.value)
        assertEquals("増えた play() は p2 の開始の 1 回だけ", s.playCountBefore + 1, s.player.playCallCount)
        assertEquals("https://example.com/p2.mp3", s.player.prepareCalls.last())
    }

    @Test
    fun `android-possync-53 shared-sync-10 端末の記録の読みの間に別の入口の再生が起きたら確認も再生もしない`() = runTest {
        val s = givenPausedWithOtherDeviceRecord()
        val gate = CompletableDeferred<Unit>()
        val readsBefore = store.readCalls
        store.readGate = gate
        launch { s.coordinator.togglePlayPause() }
        runCurrent()
        assertEquals("Given: 端末の記録の読みで止まっている", readsBefore + 1, store.readCalls)
        emit(s.player, PlaybackState.Playing(30.0, 600.0))

        gate.complete(Unit)
        runCurrent()

        assertNull(s.coordinator.resumePrompt.value)
        assertEquals(s.playCountBefore, s.player.playCallCount)

        s.coordinator.stopForSubjectLeave()
    }

    // --- 印の単調さ ---

    @Test
    fun `android-possync-55 shared-sync-13 遅れて返る古い時刻の記録が印を戻さない`() = runTest {
        val tA = tP
        val tM = tP.plusSeconds(10)
        val tB = tP.plusSeconds(20)
        val apiClient = apiClient()
        val player = FakePlayerController()
        val coordinator = wire(apiClient, player)
        coordinator.playNow("p1")
        emit(player, PlaybackState.Playing(0.0, 600.0))
        player.setPosition(30.0)
        val gate = CompletableDeferred<Unit>()
        updateGate = gate

        clock.now = tA
        emit(player, PlaybackState.Paused(30.0, 600.0))
        emit(player, PlaybackState.Playing(30.0, 600.0))
        clock.now = tB
        emit(player, PlaybackState.Paused(30.0, 600.0))
        assertEquals("Given: 1 回目の送信は応答待ちで、2 回目が先に済んでいる", listOf(tA, tB), apiClient.updatePlaybackPositionRecordedAts)
        gate.complete(Unit)
        runCurrent()
        store.seed(otherDevice(90.0, tM))
        val playsBefore = player.playCallCount

        coordinator.togglePlayPause()

        assertNull("印が tA に戻ると tM の方が新しく差が 60 なので確認が出てしまう", coordinator.resumePrompt.value)
        assertEquals(playsBefore + 1, player.playCallCount)
    }
}
