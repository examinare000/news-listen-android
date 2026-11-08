package com.rioikeda.newslisten.podcast.app

import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.network.EpisodeDecoder
import com.rioikeda.newslisten.network.AudioCacheManager
import com.rioikeda.newslisten.network.FakeFileSystem
import com.rioikeda.newslisten.network.StubNetworkMonitor
import com.rioikeda.newslisten.podcast.FakeEpisodeLookup
import com.rioikeda.newslisten.podcast.FakePlayerController
import com.rioikeda.newslisten.podcast.FakePodcastApiClient
import com.rioikeda.newslisten.podcast.OfflineLibrary
import com.rioikeda.newslisten.podcast.PlaybackFailureReason
import com.rioikeda.newslisten.podcast.PlaybackSession
import com.rioikeda.newslisten.podcast.PlaybackState
import com.rioikeda.newslisten.preferences.InMemoryPreferencesStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** [PlaybackCoordinator.queueView]（画面が読むキューのリードモデル）の検証。 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueViewTest {

    private fun podcast(
        id: String,
        status: String = "completed",
        japaneseIntroText: String = "intro-$id",
        difficulty: String = "toeic_600",
        durationSeconds: Int = 300,
    ): Episode = EpisodeDecoder.decode(
        PodcastResponse(
            id = id,
            type = "daily",
            articleIds = listOf("a1"),
            difficulty = difficulty,
            audioUrl = "https://example.com/$id.mp3",
            japaneseIntroText = japaneseIntroText,
            durationSeconds = durationSeconds,
            status = status,
            errorMessage = null,
            playbackPositionSeconds = 0.0,
            createdAt = "2026-07-01T00:00:00Z",
        ),
    )

    private class Fixture(val coordinator: PlaybackCoordinator, val player: FakePlayerController)

    private fun TestScope.fixture(vararg episodes: Episode, fetchGates: Map<String, CompletableDeferred<Unit>> = emptyMap()): Fixture {
        val byId = episodes.associateBy { it.id }
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id ->
                fetchGates[id]?.await()
                byId.getValue(id)
            },
            onUpdatePlaybackPosition = { id, _ -> byId.getValue(id) },
        )
        val lookup = FakeEpisodeLookup().also { it.register(*episodes) }
        val player = FakePlayerController()
        val coordinator = PlaybackCoordinator(
            podcastApi = apiClient,
            playerController = player,
            offlineLibrary = OfflineLibrary(
                AudioCacheManager(FakeFileSystem(), baseDir = "/cache"),
                apiClient,
                lookup,
                { "u1" },
                StandardTestDispatcher(testScheduler),
            ),
            networkMonitor = StubNetworkMonitor(initialIsOnline = true),
            preferencesStore = InMemoryPreferencesStore(),
            listeningStreakStore = null,
            dispatcher = StandardTestDispatcher(testScheduler),
            currentSubject = { "u1" },
            episodeLookup = lookup,
        )
        return Fixture(coordinator, player)
    }

    private fun TestScope.emit(player: FakePlayerController, state: PlaybackState) {
        player.setState(state)
        runCurrent()
    }

    @Test
    fun `android-dec-67 NothingPlayingではQueueViewのnowPlayingはnull`() = runTest {
        val f = fixture(podcast("p1"))

        assertNull(f.coordinator.queueView.value.nowPlaying)
    }

    @Test
    fun `android-dec-67 Erroredではキューに現在の要素があってもQueueViewのnowPlayingはnull`() = runTest {
        val p1 = podcast("p1")
        val f = fixture(p1)
        f.coordinator.playNow(p1.id)
        emit(f.player, PlaybackState.Playing(0.0, 300.0))
        emit(f.player, PlaybackState.Failed(PlaybackFailureReason.Source))

        assertTrue(f.coordinator.session.value is PlaybackSession.Errored)
        assertNull(f.coordinator.nowPlaying.value)
        assertNull(f.coordinator.queueView.value.nowPlaying)

        f.coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-dec-67 StartingとActiveとStoppedではQueueViewのnowPlayingは非null`() = runTest {
        val p1 = podcast("p1")
        val f = fixture(p1)

        f.coordinator.playNow(p1.id)
        assertTrue(f.coordinator.session.value is PlaybackSession.Starting)
        assertEquals("p1", f.coordinator.queueView.value.nowPlaying?.episodeId)

        emit(f.player, PlaybackState.Playing(0.0, 300.0))
        assertTrue(f.coordinator.session.value is PlaybackSession.Active)
        assertEquals("p1", f.coordinator.queueView.value.nowPlaying?.episodeId)

        emit(f.player, PlaybackState.Ended(300.0)) // 待機列が空なので終端の Stopped へ進む
        assertTrue(f.coordinator.session.value is PlaybackSession.Stopped)
        assertEquals("p1", f.coordinator.queueView.value.nowPlaying?.episodeId)

        f.coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-dec-67 Completedのままでも次の取得を待つ間はQueueViewのnowPlayingは非null`() = runTest {
        val (p1, p2) = listOf("p1", "p2").map { podcast(it) }
        val gate = CompletableDeferred<Unit>()
        val f = fixture(p1, p2, fetchGates = mapOf("p2" to gate))
        f.coordinator.playNow(p1.id)
        f.coordinator.addToQueue(p2.id)
        emit(f.player, PlaybackState.Playing(0.0, 300.0))

        emit(f.player, PlaybackState.Ended(300.0)) // 自動の前進が p2 の取得で待たされる

        assertEquals(PlaybackSession.Completed(p1 as Episode.Playable), f.coordinator.session.value)
        assertEquals("p1", f.coordinator.queueView.value.nowPlaying?.episodeId)
        assertEquals(listOf("p2"), f.coordinator.queueView.value.upNext.map { it.episodeId })

        gate.complete(Unit)
        runCurrent()
        f.coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-dec-66 QueueEntryのdisplayTitleはjapaneseIntroTextで他の項目も写る`() = runTest {
        val p1 = podcast("p1", japaneseIntroText = "今日のニュース", difficulty = "toeic_800", durationSeconds = 123)
        val q1 = podcast("q1", japaneseIntroText = "次のニュース", difficulty = "toeic_600", durationSeconds = 45)
        val f = fixture(p1, q1)
        f.coordinator.playNow(p1.id)
        f.coordinator.addToQueue(q1.id)

        val view = f.coordinator.queueView.value

        assertEquals("p1", view.nowPlaying?.episodeId)
        assertEquals("今日のニュース", view.nowPlaying?.displayTitle)
        assertEquals("toeic_800", view.nowPlaying?.difficultyCode)
        assertEquals(123, view.nowPlaying?.durationSeconds)
        val next = view.upNext.single()
        assertEquals("q1", next.episodeId)
        assertEquals("次のニュース", next.displayTitle)
        assertEquals("toeic_600", next.difficultyCode)
        assertEquals(45, next.durationSeconds)

        f.coordinator.stopForSubjectLeave()
    }

    @Test
    fun `待機列が0件と1件と3件のときcanMoveUpとcanMoveDownは位置で決まりupNextCountは件数と一致する`() = runTest {
        val (p, a, b, c) = listOf("p", "a", "b", "c").map { podcast(it) }
        val f = fixture(p, a, b, c)
        f.coordinator.playNow(p.id)

        assertTrue(f.coordinator.queueView.value.upNext.isEmpty())
        assertEquals(0, f.coordinator.queueView.value.upNextCount)

        f.coordinator.addToQueue(a.id)
        val one = f.coordinator.queueView.value
        assertEquals(1, one.upNextCount)
        assertFalse(one.upNext.single().canMoveUp)
        assertFalse(one.upNext.single().canMoveDown)

        f.coordinator.addToQueue(b.id)
        f.coordinator.addToQueue(c.id)
        val three = f.coordinator.queueView.value
        assertEquals(3, three.upNextCount)
        assertEquals(three.upNext.size, three.upNextCount)
        assertEquals(listOf("a", "b", "c"), three.upNext.map { it.episodeId })
        assertEquals(listOf(false, true, true), three.upNext.map { it.canMoveUp })
        assertEquals(listOf(true, true, false), three.upNext.map { it.canMoveDown })

        f.coordinator.stopForSubjectLeave()
    }

    @Test
    fun `queueViewは操作の直後に同期で読むと新しい値を返しcollectは変わるたびに出す`() = runTest {
        val (p, a, b) = listOf("p", "a", "b").map { podcast(it) }
        val f = fixture(p, a, b)
        val counts = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.coordinator.queueView.collect { counts += it.upNextCount }
        }
        runCurrent()

        f.coordinator.playNow(p.id)
        f.coordinator.addToQueue(a.id)
        assertEquals(1, f.coordinator.queueView.value.upNextCount) // 書込の直後の同期の読み
        f.coordinator.addToQueue(b.id)
        assertEquals(2, f.coordinator.queueView.value.upNextCount)
        runCurrent()

        assertEquals(listOf(0, 1, 2), counts.distinct())
        assertEquals(2, counts.last())

        f.coordinator.stopForSubjectLeave()
    }
}
