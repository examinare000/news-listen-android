package com.rioikeda.newslisten.podcast.app

import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.core.PlaybackSpeed
import com.rioikeda.newslisten.engagement.ListeningStreakStore
import com.rioikeda.newslisten.model.ListeningStreakResponse
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.network.AudioCacheManager
import com.rioikeda.newslisten.network.EpisodeDecoder
import com.rioikeda.newslisten.network.FakeFileSystem
import com.rioikeda.newslisten.network.StubNetworkMonitor
import com.rioikeda.newslisten.podcast.FakeEpisodeLookup
import com.rioikeda.newslisten.podcast.FakePlayerController
import com.rioikeda.newslisten.podcast.FakePodcastApiClient
import com.rioikeda.newslisten.podcast.OfflineLibrary
import com.rioikeda.newslisten.podcast.PlaybackConstants
import com.rioikeda.newslisten.podcast.PlaybackFailureReason
import com.rioikeda.newslisten.podcast.PlaybackSession
import com.rioikeda.newslisten.podcast.PlaybackSession.Active
import com.rioikeda.newslisten.podcast.PlaybackSession.Completed
import com.rioikeda.newslisten.podcast.PlaybackSession.Errored
import com.rioikeda.newslisten.podcast.PlaybackSession.NothingPlaying
import com.rioikeda.newslisten.podcast.PlaybackSession.Starting
import com.rioikeda.newslisten.podcast.PlaybackSession.Stopped
import com.rioikeda.newslisten.podcast.PlaybackState
import com.rioikeda.newslisten.podcast.SessionEpisodeRef
import com.rioikeda.newslisten.podcast.SessionErrorReason
import com.rioikeda.newslisten.podcast.playableWithContent
import com.rioikeda.newslisten.podcast.toPlaybackMetadata
import com.rioikeda.newslisten.preferences.InMemoryPreferencesStore
import com.rioikeda.newslisten.preferences.PreferencesStore
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlaybackCoordinator] の挙動検証。もとは `PodcastViewModelTest` の再生の部分で、期待値を変えずに移した
 * （テスト名と行 ID を保つ。shared-session-11 は `session.stop()` の辺、shared-session-12 は `session.failBeforeStart(...)` の辺で観測する）。
 * 末尾に、利用者の開始を自動より優先する規則（android-rule-playback-11・shared-dec-20・shared-session-17）を足した。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Podcast/PodcastViewModel.swift のミラー
 * （再生 URL の再取得のみ ADR-009/spec §6.1-2 準拠で意図的に改善）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackCoordinatorTest {

    private fun podcast(
        id: String = "p1",
        status: String = "completed",
        audioUrl: String = "https://example.com/$id-stale.mp3",
        errorMessage: String? = null,
        playbackPositionSeconds: Double = 0.0,
        durationSeconds: Int = 300,
    ): Episode = EpisodeDecoder.decode(
        PodcastResponse(
            id = id,
            type = "daily",
            articleIds = listOf("a1"),
            difficulty = "toeic_600",
            audioUrl = audioUrl,
            japaneseIntroText = "intro",
            durationSeconds = durationSeconds,
            status = status,
            errorMessage = errorMessage,
            playbackPositionSeconds = playbackPositionSeconds,
            createdAt = "2026-07-01T00:00:00Z",
        ),
    )

    // 呼出の直前に使う Episode を登録する（android-dec-64: command は id を受け、解決は lookup が行う）。
    private val lookup = FakeEpisodeLookup()

    private suspend fun PlaybackCoordinator.playNow(episode: Episode) {
        lookup.register(episode)
        playNow(episode.id)
    }

    private suspend fun PlaybackCoordinator.playNext(episode: Episode) {
        lookup.register(episode)
        playNext(episode.id)
    }

    private suspend fun PlaybackCoordinator.addToQueue(episode: Episode) {
        lookup.register(episode)
        addToQueue(episode.id)
    }

    private fun TestScope.newCoordinator(
        apiClient: FakePodcastApiClient,
        playerController: FakePlayerController,
        cacheManager: AudioCacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache"),
        networkMonitor: StubNetworkMonitor = StubNetworkMonitor(initialIsOnline = true),
        listeningStreakStore: ListeningStreakStore? = null,
        preferencesStore: PreferencesStore = InMemoryPreferencesStore(),
        currentSubject: () -> String? = { "u1" },
    ): PlaybackCoordinator =
        PlaybackCoordinator(
            podcastApi = apiClient,
            playerController = playerController,
            offlineLibrary = OfflineLibrary(cacheManager, apiClient, lookup, currentSubject, StandardTestDispatcher(testScheduler)),
            episodeLookup = lookup,
            networkMonitor = networkMonitor,
            preferencesStore = preferencesStore,
            listeningStreakStore = listeningStreakStore,
            dispatcher = StandardTestDispatcher(testScheduler),
            currentSubject = currentSubject,
        )

    /** shared-session-04（shared-inv-01）: 再生中のものとキューの現在位置が食い違わない。公開操作の後に毎回確かめる。 */
    private fun assertSharedInv01(coordinator: PlaybackCoordinator) {
        val currentId = coordinator.queue.value.current?.id
        assertTrue(
            "shared-session-04: session=${coordinator.session.value} queue.current=$currentId",
            coordinator.session.value.satisfiesInvariantP1(currentId),
        )
    }

    // --- play: 署名付き URL の再取得（spec §6.1-2 / ADR-009。iOS は一覧 URL 依存だが意図的改善） ---

    @Test
    fun playはfetchPodcastで最新URLを再取得してprepareする() = runTest {
        val listPodcast = podcast(id = "p1", audioUrl = "https://example.com/p1-stale.mp3")
        val freshPodcast = podcast(id = "p1", audioUrl = "https://example.com/p1-fresh.mp3")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { freshPodcast },
            onUpdatePlaybackPosition = { _, _ -> freshPodcast },
        )
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.playNow(listPodcast)

        assertEquals(listOf("p1"), apiClient.fetchPodcastCalls)
        assertEquals(listOf("https://example.com/p1-fresh.mp3"), player.prepareCalls)
        assertEquals(listOf(freshPodcast.toPlaybackMetadata()), player.metadataCalls)
        assertEquals(1, player.playCallCount)
        assertEquals(freshPodcast.id, coordinator.nowPlaying.value?.episodeId)
        assertNull(coordinator.notice.value)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-dec-08 play失敗でnoticeに反映されprepareは呼ばれない`() = runTest {
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        val apiClient = FakePodcastApiClient(onFetchPodcast = { throw exception })
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.playNow(podcast())

        assertEquals("オフラインです。接続を確認してから、もう一度お試しください", coordinator.notice.value?.message)
        assertTrue(player.prepareCalls.isEmpty())
    }

    @Test
    fun `android-dec-08 NetworkErrorの取得失敗はFetchOfflineになり例外のmessageを出さない`() = runTest {
        val player = FakePlayerController()
        val coordinator = newCoordinator(FakePodcastApiClient(onFetchPodcast = { throw ApiException.NetworkError(IOException("boom")) }), player)

        coordinator.playNow(podcast())

        assertEquals(PlaybackNotice.FetchOffline, coordinator.notice.value)
        assertFalse("例外の message が画面に出る", coordinator.notice.value?.message.orEmpty().contains("boom"))
    }

    @Test
    fun `android-dec-06 NetworkError以外のApiExceptionの取得失敗はFetchFailedになり例外のmessageを出さない`() = runTest {
        val player = FakePlayerController()
        val coordinator = newCoordinator(FakePodcastApiClient(onFetchPodcast = { throw ApiException.HttpError(500) }), player)

        coordinator.playNow(podcast())

        assertEquals(PlaybackNotice.FetchFailed, coordinator.notice.value)
        assertEquals("エピソードを取得できませんでした。通信状況を確かめて、もう一度お試しください", coordinator.notice.value?.message)
        assertFalse("HTTP Error 500 が画面に出る", coordinator.notice.value?.message.orEmpty().contains("HTTP Error 500"))
        assertTrue(player.prepareCalls.isEmpty())
    }

    @Test
    fun `android-dec-06 取り直した値がquota_exhaustedのFailedなら上限に達した旨の知らせを出し識別子の生の値は出さない`() = runTest {
        val player = FakePlayerController()
        val apiClient = FakePodcastApiClient(onFetchPodcast = { podcast(status = "failed", errorMessage = "quota_exhausted") })
        val coordinator = newCoordinator(apiClient, player)

        coordinator.playNow(podcast())

        assertEquals(PlaybackNotice.GenerationFailed(GenerationFailureReason.QUOTA_EXHAUSTED), coordinator.notice.value)
        assertEquals("生成の上限に達したため、生成できませんでした", coordinator.notice.value?.message)
        assertFalse("識別子の生の値が画面に出る", coordinator.notice.value?.message.orEmpty().contains("quota_exhausted"))
        assertTrue(player.prepareCalls.isEmpty())
    }

    @Test
    fun `android-dec-06 一覧の判定がquota_exhaustedのFailedなら取得せず上限に達した旨の知らせを出す`() = runTest {
        val apiClient = FakePodcastApiClient()
        val coordinator = newCoordinator(apiClient, FakePlayerController())

        coordinator.playNow(podcast(status = "failed", errorMessage = "quota_exhausted"))

        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
        assertEquals("生成の上限に達したため、生成できませんでした", coordinator.notice.value?.message)
    }

    // --- play: processing/failed の再生ゲート（iOS は暗黙失敗するが Android は明示ガード） ---

    @Test
    fun processingのpodcastはfetchPodcastを呼ばず再生不可メッセージを設定する() = runTest {
        val apiClient = FakePodcastApiClient()
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.playNow(podcast(status = "processing"))

        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
        assertTrue(player.prepareCalls.isEmpty())
        assertEquals("生成中のため再生できません", coordinator.notice.value?.message)
    }

    @Test
    fun `android-dec-06 failedのpodcastは識別子を生成の失敗の知らせに写す`() = runTest {
        val apiClient = FakePodcastApiClient()
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.playNow(podcast(status = "failed", errorMessage = "TTS生成に失敗しました"))

        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
        assertEquals("生成に失敗しました", coordinator.notice.value?.message)
    }

    @Test
    fun partial_failedのpodcastはfailedと同じくゲートされる() = runTest {
        val apiClient = FakePodcastApiClient()
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.playNow(podcast(status = "partial_failed", errorMessage = null))

        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
        assertEquals("生成に失敗しました", coordinator.notice.value?.message)
    }

    // --- オフライン再生（フェーズ8-C: resolvePlaybackSource 統合。正本: shared-playback-spec.md §6.1） ---

    @Test
    fun キャッシュ済みかつオフラインならfetchPodcastを呼ばずキャッシュURIでprepareして再生する() = runTest {
        val p1 = podcast(id = "p1")
        // fetchPodcast/fetchPodcasts は未スタブのままにし、呼ばれたら即座にテスト失敗させる
        // （CACHED 経路は fetch 系に一切触れない独立経路であることの検証）。
        // updatePlaybackPosition は切り替えの停止の最終同期で呼ばれるためスタブしておく。
        val apiClient = FakePodcastApiClient(onUpdatePlaybackPosition = { _, _ -> p1 })
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        cacheManager.cache("u1", "p1", byteArrayOf(1, 2, 3))
        val networkMonitor = StubNetworkMonitor(initialIsOnline = false)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player, cacheManager, networkMonitor)

        coordinator.playNow(p1)

        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
        assertEquals(listOf("file:///cache/audio/u1/p1.mp3"), player.prepareCalls)
        assertEquals(listOf(p1.toPlaybackMetadata()), player.metadataCalls)
        assertEquals(1, player.playCallCount)
        assertEquals(p1.id, coordinator.nowPlaying.value?.episodeId)
        assertNull(coordinator.notice.value)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun キャッシュ済みならオンラインでもfetchPodcastを呼ばずキャッシュを優先する() = runTest {
        // spec §6.1: キャッシュ有は署名 URL 失効と無関係に常に最優先（オンライン時も再取得しない）。
        val p1 = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(onUpdatePlaybackPosition = { _, _ -> p1 })
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        cacheManager.cache("u1", "p1", byteArrayOf(1))
        val networkMonitor = StubNetworkMonitor(initialIsOnline = true)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player, cacheManager, networkMonitor)

        coordinator.playNow(p1)

        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
        assertEquals(listOf("file:///cache/audio/u1/p1.mp3"), player.prepareCalls)
        assertEquals(p1.id, coordinator.nowPlaying.value?.episodeId)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun 未キャッシュかつオフラインなら再生できずエラーメッセージを設定する() = runTest {
        val apiClient = FakePodcastApiClient()
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val networkMonitor = StubNetworkMonitor(initialIsOnline = false)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player, cacheManager, networkMonitor)

        coordinator.playNow(podcast(id = "p1"))

        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
        assertTrue(player.prepareCalls.isEmpty())
        assertEquals(0, player.playCallCount)
        assertEquals("オフラインのため再生できません", coordinator.notice.value?.message)
    }

    @Test
    fun キャッシュ済み再生中はオフラインでもNetworkErrorを握って位置同期を継続する() = runTest {
        // spec §6.2: オフライン中の位置同期は行われない設計だが、実装は「送信して失敗を握る」
        // 既存方針（iOS syncPlaybackPositionIfNeeded 忠実写像）を維持する。ここでは
        // ApiException.NetworkError を投げても coordinator がクラッシュ・エラー表示せず
        // 継続することを保証する。
        val p1 = podcast(id = "p1")
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        val apiClient = FakePodcastApiClient(onUpdatePlaybackPosition = { _, _ -> throw exception })
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        cacheManager.cache("u1", "p1", byteArrayOf(1))
        val networkMonitor = StubNetworkMonitor(initialIsOnline = false)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player, cacheManager, networkMonitor)

        coordinator.playNow(p1)
        emitPlaying(player)
        player.setPosition(12.5)
        advanceTimeBy(15_000)
        runCurrent()

        assertNull(coordinator.notice.value)

        coordinator.stopForSubjectLeave()
    }

    // --- 位置同期（15秒毎。ストリーク起点。iOS PodcastViewModel.swift:580-603 忠実写像） ---

    @Test
    fun 再生中は15秒毎にPATCHで再生位置を同期する() = runTest {
        val fresh = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { fresh },
            onUpdatePlaybackPosition = { _, _ -> fresh },
        )
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.playNow(podcast(id = "p1"))
        emitPlaying(player)
        player.setPosition(12.5)
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(listOf("p1" to 12.5, "p1" to 12.5), apiClient.updatePlaybackPositionCalls)

        coordinator.stopForSubjectLeave()
    }

    // --- スキップ（PlaybackConstants: 戻る15秒 / 進む30秒） ---

    @Test
    fun skipBackwardは現在位置から15秒戻ってseekToに伝わり下限0でクランプする() = runTest {
        val fresh = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { fresh },
            onUpdatePlaybackPosition = { _, _ -> fresh },
        )
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(podcast(id = "p1"))

        player.setPosition(50.0)
        coordinator.skipBackward()
        assertEquals(listOf(35.0), player.seekCalls)

        player.setPosition(10.0)
        coordinator.skipBackward()
        assertEquals(listOf(35.0, 0.0), player.seekCalls)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun skipForwardは現在位置から30秒進んでseekToに伝わりdurationで上限クランプする() = runTest {
        val fresh = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { fresh },
            onUpdatePlaybackPosition = { _, _ -> fresh },
        )
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(podcast(id = "p1"))

        player.setPosition(50.0)
        coordinator.skipForward()
        assertEquals(listOf(80.0), player.seekCalls)

        player.setDuration(100.0)
        player.setPosition(90.0)
        coordinator.skipForward()
        assertEquals(listOf(80.0, 100.0), player.seekCalls)

        coordinator.stopForSubjectLeave()
    }

    // --- 速度8段階の伝播 ---

    @Test
    fun `shared-session-10 setSpeedは開始時の既定速度に続けて8段階全てがplayerControllerに伝播する`() = runTest {
        val fresh = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { fresh },
            onUpdatePlaybackPosition = { _, _ -> fresh },
        )
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(podcast(id = "p1"))

        PlaybackConstants.speeds.forEach { coordinator.setSpeed(it) }

        // 先頭の 1 件は、開始の確定時に player へ設定した既定速度（InMemoryPreferencesStore の既定 1.0）。
        assertEquals(listOf(1.0f) + PlaybackConstants.speeds.map { it.toFloat() }, player.speedCalls)

        coordinator.stopForSubjectLeave()
    }

    // --- 再生完了で停止 ---

    @Test
    fun `再生完了イベントではキュー遷移前のPodcast IDを完聴記録する`() = runTest {
        val fresh = podcast(id = "p1")
        lateinit var coordinator: PlaybackCoordinator
        var currentIdWhenMarked: String? = null
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { fresh },
            onUpdatePlaybackPosition = { _, _ -> fresh },
            onMarkCompleted = { currentIdWhenMarked = coordinator.nowPlaying.value?.episodeId },
        )
        val player = FakePlayerController()
        coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(podcast(id = "p1"))
        player.setPosition(99.0)

        emit(player, PlaybackState.Ended(300.0))

        assertEquals(listOf("p1"), apiClient.markCompletedCalls)
        assertEquals("p1", currentIdWhenMarked)
        assertEquals(1, player.stopCallCount)
        assertEquals(fresh.id, coordinator.nowPlaying.value?.episodeId)
        // 完聴で送る位置は Ended が運ぶ総時間（shared-session-07）。
        assertEquals(listOf("p1" to 300.0), apiClient.updatePlaybackPositionCalls)

        // タイマーは既に止まっているため、時間を進めても追加の PATCH は飛ばない。
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf("p1" to 300.0), apiClient.updatePlaybackPositionCalls)
    }

    @Test
    fun `完聴記録が失敗しても次のPodcastへ遷移してstreakを再取得する`() = runTest {
        val p1 = podcast(id = "p1")
        val p2 = podcast(id = "p2")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id -> if (id == "p1") p1 else p2 },
            onUpdatePlaybackPosition = { id, _ -> if (id == "p1") p1 else p2 },
            onMarkCompleted = { throw ApiException.NetworkError(IOException("offline")) },
        )
        val streakStore = FakeListeningStreakStore()
        val player = FakePlayerController()
        val coordinator = newCoordinator(
            apiClient = apiClient,
            playerController = player,
            listeningStreakStore = streakStore,
        )
        coordinator.playNow(p1)
        coordinator.playNext(p2)

        emit(player, PlaybackState.Ended(300.0))

        assertEquals(p2.id, coordinator.nowPlaying.value?.episodeId)
        assertEquals(1, streakStore.refreshCallCount)
        assertEquals(2, player.playCallCount)
        coordinator.stopForSubjectLeave()
    }

    // --- PlayerController の release()/stop() 契約
    //     （実機 ExoPlayer は release() 後に再利用不可。エピソード切替は stop() を使うべき） ---

    @Test
    fun 再生停止後に別のPodcastを再生できる() = runTest {
        val podcastA = podcast(id = "pA")
        val podcastB = podcast(id = "pB")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id -> if (id == "pA") podcastA else podcastB },
            onUpdatePlaybackPosition = { id, _ -> if (id == "pA") podcastA else podcastB },
        )
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.playNow(podcast(id = "pA"))
        coordinator.stopForSubjectLeave()
        // ここで PlayerController が release() 済みだと Fake が AssertionError を投げる
        // （実機 ExoPlayer の「release 後は再利用不可」契約をテストで顕在化させる）。
        coordinator.playNow(podcast(id = "pB"))

        assertEquals(2, player.prepareCalls.size)
        assertEquals(2, player.playCallCount)
        assertEquals(podcastB.id, coordinator.nowPlaying.value?.episodeId)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun 再生中に別のPodcastへ直接切り替えるとAの同期が止まりBのみ継続する() = runTest {
        val podcastA = podcast(id = "pA")
        val podcastB = podcast(id = "pB")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id -> if (id == "pA") podcastA else podcastB },
            onUpdatePlaybackPosition = { id, _ -> if (id == "pA") podcastA else podcastB },
        )
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.playNow(podcast(id = "pA"))
        emitPlaying(player)
        player.setPosition(20.0)

        // stopForSubjectLeave() を挟まず直接 B へ切り替える（play() 冒頭の内部停止処理を経由）。
        coordinator.playNow(podcast(id = "pB"))
        emit(player, PlaybackState.Loading)
        emitPlaying(player)

        assertEquals(podcastB.id, coordinator.nowPlaying.value?.episodeId)
        // 切り替え時に A の最終位置が一度だけ同期される。
        assertEquals(listOf("pA" to 20.0), apiClient.updatePlaybackPositionCalls)

        apiClient.updatePlaybackPositionCalls.clear()
        player.setPosition(5.0)
        advanceTimeBy(15_000)
        runCurrent()

        // 以後は B の同期のみが継続し、A の同期は再開しない。
        assertEquals(listOf("pB" to 5.0), apiClient.updatePlaybackPositionCalls)

        coordinator.stopForSubjectLeave()
    }

    // --- play() の suspend 境界競合（孤児タイマー防止） ---

    @Test
    fun play連続呼び出しでも同期タイマーは後勝ちの1系統のみ継続する() = runTest {
        val podcastA = podcast(id = "pA")
        val podcastB = podcast(id = "pB")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id ->
                // fetchPodcast に遅延を入れ、2つの play() 呼び出しの suspend 境界を競合させる。
                delay(1_000)
                if (id == "pA") podcastA else podcastB
            },
            onUpdatePlaybackPosition = { id, _ -> if (id == "pA") podcastA else podcastB },
        )
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        launch { coordinator.playNow(podcast(id = "pA")) }
        launch { coordinator.playNow(podcast(id = "pB")) }
        // WHY advanceUntilIdle() を使わない: startPositionSync の while(isActive) ループは
        // 永久に次の delay(15000) を積み続けるため、advanceUntilIdle() は終了しない
        // （既存テストが advanceTimeBy + runCurrent の有界な組み合わせを使っているのと同じ理由）。
        // ここでは両方の fetchPodcast(delay 1_000) が解決するのに十分な 2_000ms だけ進める。
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(podcastB.id, coordinator.nowPlaying.value?.episodeId)
        emitPlaying(player) // Starting の B が Active になって周期の timer を張る

        apiClient.updatePlaybackPositionCalls.clear()
        player.setPosition(3.0)
        advanceTimeBy(15_000)
        runCurrent()

        // A 側の同期タイマーが孤児化して生き残っていれば "pA" への PATCH も混ざって観測される。
        assertEquals(listOf("pB" to 3.0), apiClient.updatePlaybackPositionCalls)

        coordinator.stopForSubjectLeave()
    }

    // --- 再生キュー統合（フェーズ6 T1: PlaybackQueue<Episode> + 自動次遷移） ---

    private fun queueApiClient(vararg podcasts: Episode): FakePodcastApiClient {
        val byId = podcasts.associateBy { it.id }
        return FakePodcastApiClient(
            onFetchPodcast = { id -> byId.getValue(id) },
            onUpdatePlaybackPosition = { id, _ -> byId.getValue(id) },
        )
    }

    @Test
    fun playNowはキューに無ければ現在の次に挿入してジャンプし再生する() = runTest {
        val p1 = podcast(id = "p1")
        val apiClient = queueApiClient(p1)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.playNow(p1)

        assertEquals(p1.id, coordinator.nowPlaying.value?.episodeId)
        assertEquals(listOf(p1), coordinator.queue.value.items)
        assertEquals(p1, coordinator.queue.value.current)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun playNowはキュー内に既にあれば重複挿入せずジャンプして再生する() = runTest {
        val p1 = podcast(id = "p1")
        val p2 = podcast(id = "p2")
        val apiClient = queueApiClient(p1, p2)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.playNow(p1)
        coordinator.playNext(p2) // キュー = [p1*, p2]（再生中は変わらない）

        coordinator.playNow(p2)

        assertEquals(p2.id, coordinator.nowPlaying.value?.episodeId)
        assertEquals(listOf(p1, p2), coordinator.queue.value.items)
        assertEquals(p2, coordinator.queue.value.current)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun playNowは再生中に未登録の別Podcastを指定するとキューに追加され切り替わって再生される() = runTest {
        val p1 = podcast(id = "p1")
        val p2 = podcast(id = "p2")
        val apiClient = queueApiClient(p1, p2)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.playNow(p1)
        coordinator.playNow(p2)

        assertEquals(listOf(p1, p2), coordinator.queue.value.items)
        assertEquals(p2, coordinator.queue.value.current)
        assertEquals(p2.id, coordinator.nowPlaying.value?.episodeId)
        assertEquals(2, player.playCallCount)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun playNextはキューに反映し未再生なら即再生する() = runTest {
        val p1 = podcast(id = "p1")
        val apiClient = queueApiClient(p1)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.playNext(p1)

        assertEquals(p1.id, coordinator.nowPlaying.value?.episodeId)
        assertEquals(listOf(p1), coordinator.queue.value.items)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun playNextは再生中なら現在の次に挿入するだけで再生を継続する() = runTest {
        val p1 = podcast(id = "p1")
        val p2 = podcast(id = "p2")
        val apiClient = queueApiClient(p1, p2)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.playNow(p1)
        coordinator.playNext(p2)

        assertEquals(p1.id, coordinator.nowPlaying.value?.episodeId)
        assertEquals(listOf(p1, p2), coordinator.queue.value.items)
        assertEquals(1, player.playCallCount)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun addToQueueはキューに反映し未再生なら即再生する() = runTest {
        val p1 = podcast(id = "p1")
        val apiClient = queueApiClient(p1)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.addToQueue(p1)

        assertEquals(p1.id, coordinator.nowPlaying.value?.episodeId)
        assertEquals(listOf(p1), coordinator.queue.value.items)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun addToQueueは再生中なら末尾に追加するだけで再生を継続する() = runTest {
        val p1 = podcast(id = "p1")
        val p2 = podcast(id = "p2")
        val apiClient = queueApiClient(p1, p2)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.playNow(p1)
        coordinator.addToQueue(p2)

        assertEquals(p1.id, coordinator.nowPlaying.value?.episodeId)
        assertEquals(listOf(p1, p2), coordinator.queue.value.items)
        assertEquals(1, player.playCallCount)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun 再生完了で次があれば自動的に次を再生する() = runTest {
        val p1 = podcast(id = "p1")
        val p2 = podcast(id = "p2")
        val apiClient = queueApiClient(p1, p2)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.playNow(p1)
        coordinator.playNext(p2)

        emit(player, PlaybackState.Ended(300.0))

        assertEquals(listOf("p1", "p2"), apiClient.fetchPodcastCalls)
        assertEquals(p2.id, coordinator.nowPlaying.value?.episodeId)
        assertEquals(p2, coordinator.queue.value.current)
        assertEquals(2, player.playCallCount)

        // 自動遷移後の位置同期は新しいエピソード（p2）へ向く。
        apiClient.updatePlaybackPositionCalls.clear()
        emitPlaying(player)
        player.setPosition(3.0)
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(listOf("p2" to 3.0), apiClient.updatePlaybackPositionCalls)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `再生完了でキュー末尾ならプレイヤーだけ停止してPodcastを保持する`() = runTest {
        val p1 = podcast(id = "p1")
        val apiClient = queueApiClient(p1)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.playNow(p1)

        emit(player, PlaybackState.Ended(300.0))

        assertEquals(p1.id, coordinator.nowPlaying.value?.episodeId)
        assertEquals(1, player.stopCallCount)
    }

    // --- 自動遷移×再生ゲートの整合性（review指摘: advance後のnextがgateされる場合に
    //     queueだけ進んでnowPlayingが旧曲のまま詰まる不整合を防ぐ） ---

    @Test
    fun 自動遷移先がprocessingの場合エラー設定して停止しqueueとnowPlayingが整合する() = runTest {
        val p1 = podcast(id = "p1")
        val p2 = podcast(id = "p2", status = "processing")
        val apiClient = queueApiClient(p1, p2)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.playNow(p1)
        coordinator.playNext(p2)

        emit(player, PlaybackState.Ended(300.0))

        assertEquals("生成中のため再生できません", coordinator.notice.value?.message)
        assertNull(coordinator.nowPlaying.value)
        assertEquals(p2, coordinator.queue.value.current)
        assertEquals(1, player.stopCallCount)
        assertTrue(apiClient.fetchPodcastCalls.none { it == "p2" })

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun removeFromQueueは待機列の要素をキューモデルから取り除き再生中には影響しない() = runTest {
        val p1 = podcast(id = "p1")
        val p2 = podcast(id = "p2")
        val apiClient = queueApiClient(p1, p2)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.playNow(p1)
        coordinator.playNext(p2)

        coordinator.removeFromQueue(p2.id)

        assertEquals(listOf(p1), coordinator.queue.value.items)
        assertEquals(p1.id, coordinator.nowPlaying.value?.episodeId)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun removeFromQueueで現在再生中のidを渡してもキューモデルの更新のみで実際の再生は継続する() = runTest {
        // iOS 準拠（PodcastViewModel.swift:313-315）: removeFromQueue は queue.remove(id:) を
        // 呼ぶだけの純粋なキュー操作で、実際の再生（AVPlayer/PlayerController）には触れない。
        // キューモデル上は次の要素が currentIndex に昇格するが、nowPlaying/実再生は不変。
        val p1 = podcast(id = "p1")
        val p2 = podcast(id = "p2")
        val apiClient = queueApiClient(p1, p2)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.playNow(p1)
        coordinator.playNext(p2)

        coordinator.removeFromQueue(p1.id)

        assertEquals(listOf(p2), coordinator.queue.value.items)
        assertEquals(p2, coordinator.queue.value.current)
        assertEquals(p1.id, coordinator.nowPlaying.value?.episodeId)
        assertEquals(0, player.stopCallCount)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun moveUpNextはqueueのStateFlowに反映される() = runTest {
        val p1 = podcast(id = "p1")
        val p2 = podcast(id = "p2")
        val p3 = podcast(id = "p3")
        val apiClient = queueApiClient(p1, p2, p3)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.playNow(p1)
        coordinator.playNext(p2)
        coordinator.playNext(p3) // 現在の直後へ挿入: キュー = [p1*, p3, p2]

        coordinator.moveUp(p2.id)

        assertEquals(listOf(p2, p3), coordinator.queue.value.upNext)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `CACHED判定の直後にキャッシュが削除されても例外にせず開始の失敗として表に出す`() = runTest {
        val p1 = podcast(id = "p1")
        val fake = FakeFileSystem()
        var existsCalls = 0
        // 1 回目の存在確認（CACHED の判定）だけ真で、以後（URI の取得）は別の dispatcher の削除後を模して偽。
        val racing = object : com.rioikeda.newslisten.network.FileSystem by fake {
            override fun exists(path: String): Boolean = existsCalls++ == 0 && fake.exists(path)
        }
        val cacheManager = AudioCacheManager(racing, baseDir = "/cache")
        cacheManager.cache("u1", "p1", byteArrayOf(1))
        val player = FakePlayerController()
        val coordinator = newCoordinator(FakePodcastApiClient(), player, cacheManager)

        coordinator.playNow(p1)

        assertTrue(player.prepareCalls.isEmpty())
        assertNotNull(coordinator.notice.value)
        assertTrue(coordinator.session.value is NothingPlaying)
    }

    // --- 再生可否は Episode の種類で判定する（shared-session-08・android-deriv-26・android-dec-22） ---

    @Test
    fun `shared-session-08 android-dec-06 completedでもerrorMessageを持つ行はfetchせず再生不可で生成の失敗の知らせを出す`() = runTest {
        val apiClient = FakePodcastApiClient()
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.playNow(podcast(status = "completed", errorMessage = "X"))

        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
        assertTrue(player.prepareCalls.isEmpty())
        assertEquals("生成に失敗しました", coordinator.notice.value?.message)
    }

    @Test
    fun `android-deriv-26 completedでもURLが空の行はfetchせず再生不可になる`() = runTest {
        val apiClient = FakePodcastApiClient()
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.playNow(podcast(status = "completed", audioUrl = ""))

        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
        assertTrue(player.prepareCalls.isEmpty())
        assertEquals("生成に失敗しました", coordinator.notice.value?.message)
    }

    @Test
    fun `android-deriv-26 未知のstatusの行はfetchせず再生不可になる`() = runTest {
        val apiClient = FakePodcastApiClient()
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.playNow(podcast(status = "weird"))

        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
        assertTrue(player.prepareCalls.isEmpty())
        assertEquals("生成に失敗しました", coordinator.notice.value?.message)
    }

    @Test
    fun `android-dec-22 failedでerrorMessageが空文字なら生成に失敗しましたを出す`() = runTest {
        val apiClient = FakePodcastApiClient()
        val coordinator = newCoordinator(apiClient, FakePlayerController())

        coordinator.playNow(podcast(status = "failed", errorMessage = ""))

        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
        assertEquals("生成に失敗しました", coordinator.notice.value?.message)
    }

    @Test
    fun `shared-session-08 正の対照 completedでURLがありerrorMessageが無い行は取り直した URL で再生する`() = runTest {
        val listed = podcast(id = "p1")
        val fresh = podcast(id = "p1", audioUrl = "https://example.com/p1-fresh.mp3")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { fresh },
            onUpdatePlaybackPosition = { _, _ -> fresh },
        )
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.playNow(listed)

        assertEquals(listOf("p1"), apiClient.fetchPodcastCalls)
        assertEquals(listOf("https://example.com/p1-fresh.mp3"), player.prepareCalls)
        assertEquals(1, player.playCallCount)
        assertNull(coordinator.notice.value)

        coordinator.stopForSubjectLeave()
    }

    // --- 取り直した値が再生不可でも、前の再生を壊さない（android-deriv-17・android-dec-21） ---

    @Test
    fun `android-deriv-17 android-dec-06 一覧の判定で拒否されても再生中の位置同期は続く`() = runTest {
        val pA = podcast(id = "pA")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { pA },
            onUpdatePlaybackPosition = { _, _ -> pA },
        )
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        emitPlaying(player)

        coordinator.playNow(podcast(id = "pX", status = "failed", errorMessage = "boom"))
        apiClient.updatePlaybackPositionCalls.clear()
        player.setPosition(5.0)
        advanceTimeBy(15_000)
        runCurrent()

        assertEquals(listOf("pA" to 5.0), apiClient.updatePlaybackPositionCalls)
        assertEquals(pA.id, coordinator.nowPlaying.value?.episodeId)
        assertEquals("生成に失敗しました", coordinator.notice.value?.message)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-deriv-17 取り直した値がGeneratingなら前の再生を止めず生成中を出す`() = runTest {
        val pA = podcast(id = "pA")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id -> if (id == "pB") podcast(id = "pB", status = "processing") else pA },
            onUpdatePlaybackPosition = { _, _ -> pA },
        )
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)

        coordinator.playNow(podcast(id = "pB"))

        assertEquals(0, player.stopCallCount)
        assertEquals(pA.id, coordinator.nowPlaying.value?.episodeId)
        assertEquals("生成中のため再生できません", coordinator.notice.value?.message)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-deriv-17 android-dec-06 取り直した値がFailedなら前の再生を止めず生成の失敗の知らせを出す`() = runTest {
        val pA = podcast(id = "pA")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id -> if (id == "pB") podcast(id = "pB", status = "failed", errorMessage = "E") else pA },
            onUpdatePlaybackPosition = { _, _ -> pA },
        )
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)

        coordinator.playNow(podcast(id = "pB"))

        assertEquals(0, player.stopCallCount)
        assertEquals(pA.id, coordinator.nowPlaying.value?.episodeId)
        assertEquals("生成に失敗しました", coordinator.notice.value?.message)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-deriv-17 取り直した値が拒否された後も前の再生の位置同期は続く`() = runTest {
        val pA = podcast(id = "pA")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id -> if (id == "pB") podcast(id = "pB", status = "processing") else pA },
            onUpdatePlaybackPosition = { _, _ -> pA },
        )
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        emitPlaying(player)
        coordinator.playNow(podcast(id = "pB"))

        apiClient.updatePlaybackPositionCalls.clear()
        player.setPosition(8.0)
        advanceTimeBy(15_000)
        runCurrent()

        assertEquals(listOf("pA" to 8.0), apiClient.updatePlaybackPositionCalls)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-deriv-17 NETWORK再生はfetchの往復が終わるまで前の再生を止めない`() = runTest {
        val pA = podcast(id = "pA")
        val pBFresh = podcast(id = "pB", audioUrl = "https://example.com/pB-fresh.mp3")
        val fetchGate = CompletableDeferred<Unit>()
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id ->
                if (id == "pB") {
                    fetchGate.await()
                    pBFresh
                } else {
                    pA
                }
            },
            onUpdatePlaybackPosition = { id, _ -> if (id == "pA") pA else pBFresh },
        )
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        emitPlaying(player)

        launch { coordinator.playNow(podcast(id = "pB")) }
        runCurrent()

        // 観測 1: pB の fetch の途中。前の再生はまだ止まっていない。
        assertEquals(0, player.stopCallCount)
        assertFalse(player.prepareCalls.contains("https://example.com/pB-fresh.mp3"))
        assertTrue(apiClient.updatePlaybackPositionCalls.none { it.first == "pA" })

        fetchGate.complete(Unit)
        runCurrent()

        // 観測 2: fetch の後で止まり、取り直した URL で始まる。
        assertEquals(1, player.stopCallCount)
        assertEquals("https://example.com/pB-fresh.mp3", player.prepareCalls.last())
        assertEquals(1, apiClient.updatePlaybackPositionCalls.count { it.first == "pA" })
        assertEquals(pBFresh.id, coordinator.nowPlaying.value?.episodeId)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `shared-session-13 android-deriv-17 android-dec-08 fetchがApiExceptionなら前の再生を止めずnoticeを出す`() = runTest {
        val pA = podcast(id = "pA")
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id -> if (id == "pB") throw exception else pA },
            onUpdatePlaybackPosition = { _, _ -> pA },
        )
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)

        coordinator.playNow(podcast(id = "pB"))

        assertEquals(0, player.stopCallCount)
        assertEquals(pA.id, coordinator.nowPlaying.value?.episodeId)
        assertEquals("オフラインです。接続を確認してから、もう一度お試しください", coordinator.notice.value?.message)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-deriv-17 自動前進先の取り直し値がGeneratingなら止めてnowPlayingをnullにする`() = runTest {
        val pA = podcast(id = "pA")
        val pB = podcast(id = "pB")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id -> if (id == "pB") podcast(id = "pB", status = "processing") else pA },
            onUpdatePlaybackPosition = { _, _ -> pA },
        )
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.playNext(pB)

        emit(player, PlaybackState.Ended(300.0))

        assertNull(coordinator.nowPlaying.value)
        assertEquals("生成中のため再生できません", coordinator.notice.value?.message)
        assertEquals(1, player.stopCallCount)
    }

    @Test
    fun `android-deriv-17 android-dec-06 自動前進先の取り直し値がFailedなら生成の失敗の知らせを出して止める`() = runTest {
        val pA = podcast(id = "pA")
        val pB = podcast(id = "pB")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id -> if (id == "pB") podcast(id = "pB", status = "failed", errorMessage = "E") else pA },
            onUpdatePlaybackPosition = { _, _ -> pA },
        )
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.playNext(pB)

        emit(player, PlaybackState.Ended(300.0))

        assertNull(coordinator.nowPlaying.value)
        assertEquals("生成に失敗しました", coordinator.notice.value?.message)
        assertEquals(1, player.stopCallCount)
    }

    // --- CACHED / UNAVAILABLE でも前の再生は止まり、最終同期が行われる（android-dec-21） ---

    @Test
    fun `android-dec-21 CACHED再生は前の再生を止め最終同期してキャッシュURIで始める`() = runTest {
        val pA = podcast(id = "pA")
        val pB = podcast(id = "pB")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { pA },
            onUpdatePlaybackPosition = { id, _ -> if (id == "pA") pA else pB },
        )
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player, cacheManager)
        coordinator.playNow(pA)
        emitPlaying(player)
        cacheManager.cache("u1", "pB", byteArrayOf(1))

        coordinator.playNow(pB)

        assertEquals(1, player.stopCallCount)
        assertEquals(1, apiClient.updatePlaybackPositionCalls.count { it.first == "pA" })
        assertEquals(listOf("pA"), apiClient.fetchPodcastCalls)
        assertEquals("file:///cache/audio/u1/pB.mp3", player.prepareCalls.last())
        assertEquals(pB.id, coordinator.nowPlaying.value?.episodeId)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `shared-session-13 android-dec-21 UNAVAILABLE再生は前の再生を止めず最終同期もせずオフラインを出す`() = runTest {
        val pA = podcast(id = "pA")
        val pB = podcast(id = "pB")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { pA },
            onUpdatePlaybackPosition = { id, _ -> if (id == "pA") pA else pB },
        )
        val networkMonitor = StubNetworkMonitor(initialIsOnline = true)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player, networkMonitor = networkMonitor)
        coordinator.playNow(pA)
        networkMonitor.setOnline(false)

        coordinator.playNow(pB)

        assertEquals(0, player.stopCallCount)
        assertEquals(0, apiClient.updatePlaybackPositionCalls.count { it.first == "pA" })
        assertEquals(listOf("pA"), apiClient.fetchPodcastCalls)
        assertEquals(pA.id, coordinator.nowPlaying.value?.episodeId)
        assertEquals("オフラインのため再生できません", coordinator.notice.value?.message)

        coordinator.stopForSubjectLeave()
    }

    // --- play が playMutex を保持している間に届いた前の再生の完了通知では、キューを前進しない（android-dec-27） ---

    @Test
    fun `android-dec-27 別のepisodeのplayが取得中に前の再生が終わっても完了は記録しキューは前進しない`() = runTest {
        val pA = podcast(id = "pA")
        val pB = podcast(id = "pB")
        val pC = podcast(id = "pC")
        val pBFresh = podcast(id = "pB", audioUrl = "https://example.com/pB-fresh.mp3")
        val fetchGate = CompletableDeferred<Unit>()
        val markGate = CompletableDeferred<Unit>()
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id ->
                when (id) {
                    "pB" -> {
                        fetchGate.await()
                        pBFresh
                    }
                    "pC" -> pC
                    else -> pA
                }
            },
            onUpdatePlaybackPosition = { id, _ -> if (id == "pA") pA else pBFresh },
            onMarkCompleted = { markGate.await() },
        )
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.addToQueue(pB)
        coordinator.addToQueue(pC)

        // pB の play が fetch の往復で playMutex を保持している間に、pA の完了通知が届く。
        launch { coordinator.playNow(pB) }
        runCurrent()
        emit(player, PlaybackState.Ended(300.0)) // 完了の記録（markCompleted(pA)）の途中で止まる

        // 記録の途中で pB の取得が終わり、ロックが外れる。そのあとで記録が終わる。
        fetchGate.complete(Unit)
        runCurrent()
        markGate.complete(Unit)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf("pA"), apiClient.markCompletedCalls)
        assertEquals(pB, coordinator.queue.value.current)
        assertEquals(pBFresh.id, coordinator.nowPlaying.value?.episodeId)
        assertEquals("https://example.com/pB-fresh.mp3", player.prepareCalls.last())
        assertTrue(apiClient.fetchPodcastCalls.none { it == "pC" })

        coordinator.stopForSubjectLeave()
    }

    // --- 現在再生中の正本は session（android-slice-playback-03） ---

    private fun playable(episode: Episode): Episode.Playable = episode as Episode.Playable

    private fun starting(episode: Episode) = Starting(playable(episode), 0.0, 1.0)

    private fun erroredIdOnly(id: String, reason: SessionErrorReason) = Errored(SessionEpisodeRef.IdOnly(id), reason)

    private fun erroredLoaded(episode: Episode, reason: PlaybackFailureReason) =
        Errored(SessionEpisodeRef.Loaded(playable(episode)), SessionErrorReason.Player(reason))

    /** player の事象を注入し、購読が session へ反映するまで進める（同じ値の連続は届かないので呼び分ける）。 */
    private fun TestScope.emit(player: FakePlayerController, state: PlaybackState) {
        player.setState(state)
        runCurrent()
    }

    private fun TestScope.emitPlaying(player: FakePlayerController) = emit(player, PlaybackState.Playing(0.0, 300.0))

    // 既定速度と再開位置（shared-session-10・android-contract-06）

    @Test
    fun `android-test-05 shared-session-10 android-contract-06 既定速度で開始しセッション中の速度変更は既定速度を書き換えず次のepisodeも既定速度で始まる`() = runTest {
        val preferences = InMemoryPreferencesStore(initialDefaultPlaybackSpeed = PlaybackSpeed.of(1.5)!!)
        val listedA = podcast(id = "pA")
        val freshA = podcast(id = "pA", audioUrl = "https://example.com/pA-fresh.mp3", playbackPositionSeconds = 120.0)
        val listedB = podcast(id = "pB", playbackPositionSeconds = 40.0)
        val apiClient = FakePodcastApiClient(onFetchPodcast = { freshA }, onUpdatePlaybackPosition = { _, _ -> freshA })
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        cacheManager.cache("u1", "pB", byteArrayOf(1))
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player, cacheManager, preferencesStore = preferences)

        coordinator.playNow(listedA) // NETWORK: 再開位置は取り直した値（一覧の 0 ではない）
        assertEquals(listOf(1.5f), player.speedCalls)
        assertEquals(listOf(120.0), player.seekCalls)

        coordinator.setSpeed(2.0)
        coordinator.playNow(listedB) // CACHED: 再開位置は一覧の値

        assertEquals(listOf(1.5f, 2.0f, 1.5f), player.speedCalls)
        assertEquals(listOf(120.0, 40.0), player.seekCalls)
        assertEquals(1.5, preferences.defaultPlaybackSpeed.value.multiplier, 0.0)
        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-test-05 shared-session-10 android-contract-06 再開位置が0か末尾2秒窓ならseekToを呼ばず窓の手前なら適用する`() = runTest {
        val episodes = listOf(0.0, 299.0, 298.0, 297.5).mapIndexed { i, seconds ->
            podcast(id = "p$i", playbackPositionSeconds = seconds)
        }
        val player = FakePlayerController()
        val coordinator = newCoordinator(queueApiClient(*episodes.toTypedArray()), player)

        episodes.take(3).forEach { coordinator.playNow(it) }
        assertTrue(player.seekCalls.isEmpty())

        coordinator.playNow(episodes[3])
        assertEquals(listOf(297.5), player.seekCalls)
        coordinator.stopForSubjectLeave()
    }

    // 完聴後の自動前進の開始前の失敗（shared-session-01・02・03・12）

    @Test
    fun `android-test-06 shared-session-01 shared-session-02 shared-session-04 shared-session-12 android-contract-07 android-dec-08 advance後のNETWORK失敗はErroredでretryが取得からやり直す`() = runTest {
        val (pA, pB, pC, pD) = listOf("pA", "pB", "pC", "pD").map { podcast(id = it) }
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        var bFails = true
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id -> if (id == "pB") (if (bFails) throw exception else pB) else if (id == "pC") pC else pA },
            onUpdatePlaybackPosition = { _, _ -> pA },
        )
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.addToQueue(pB)
        coordinator.addToQueue(pC)
        val stopsBefore = player.stopCallCount

        emit(player, PlaybackState.Ended(300.0))

        assertEquals(erroredIdOnly("pB", SessionErrorReason.FetchFailed), coordinator.session.value)
        assertEquals(pB, coordinator.queue.value.current) // 自動で pC へ進まない
        assertEquals(stopsBefore + 1, player.stopCallCount)
        assertEquals("オフラインです。接続を確認してから、もう一度お試しください", coordinator.notice.value?.message)
        assertEquals(listOf("pA"), apiClient.markCompletedCalls)
        assertNull(coordinator.nowPlaying.value)
        assertTrue(apiClient.fetchPodcastCalls.none { it == "pC" })
        assertSharedInv01(coordinator)

        val preparesAfterFailure = player.prepareCalls.size
        coordinator.addToQueue(pD) // Errored の間は即再生しない
        assertEquals(preparesAfterFailure, player.prepareCalls.size)
        assertEquals(listOf(pA, pB, pC, pD), coordinator.queue.value.items)
        assertSharedInv01(coordinator)

        bFails = false
        coordinator.retry()

        assertEquals(listOf("pA", "pB", "pB"), apiClient.fetchPodcastCalls)
        assertEquals(starting(pB), coordinator.session.value)
        assertEquals(playable(pB).audioUrl, player.prepareCalls.last())
        assertNull(coordinator.notice.value)
        assertSharedInv01(coordinator)
        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-test-07 shared-session-03 shared-session-12 android-contract-08 advance後のオフライン未キャッシュはfetchせずSourceUnavailableのErrored`() = runTest {
        val (pA, pB) = listOf("pA", "pB").map { podcast(id = it) }
        val apiClient = queueApiClient(pA, pB)
        val networkMonitor = StubNetworkMonitor(initialIsOnline = true)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player, networkMonitor = networkMonitor)
        coordinator.playNow(pA)
        coordinator.addToQueue(pB)
        networkMonitor.setOnline(false)

        emit(player, PlaybackState.Ended(300.0))

        assertEquals(listOf("pA"), apiClient.fetchPodcastCalls)
        assertEquals(erroredIdOnly("pB", SessionErrorReason.SourceUnavailable), coordinator.session.value)
        assertEquals("オフラインのため再生できません", coordinator.notice.value?.message)
        assertEquals(pB, coordinator.queue.value.current)
        assertSharedInv01(coordinator)
        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-test-06 shared-session-12 advance先の取り直し値がGeneratingならNotPlayableのErrored`() = runTest {
        val pA = podcast(id = "pA")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id -> if (id == "pB") podcast(id = "pB", status = "processing") else pA },
            onUpdatePlaybackPosition = { _, _ -> pA },
        )
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.addToQueue(podcast(id = "pB"))

        emit(player, PlaybackState.Ended(300.0))

        assertEquals(erroredIdOnly("pB", SessionErrorReason.NotPlayable), coordinator.session.value)
        assertEquals("生成中のため再生できません", coordinator.notice.value?.message)
        assertSharedInv01(coordinator)
    }

    // 手動の開始が開始前に落ちるとき、キューも session も player も変えない（shared-session-13・shared-dec-16）

    private suspend fun TestScope.assertManualStartRejectedKeepsState(
        listedB: Episode,
        expectedMessage: String?,
        expectFetchOfB: Boolean,
        online: Boolean = true,
        fetchB: suspend () -> Episode = { listedB },
        start: suspend (PlaybackCoordinator, Episode) -> Unit = { coordinator, episode -> coordinator.playNow(episode) },
    ) {
        val pA = podcast(id = "pA")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id -> if (id == "pB") fetchB() else pA },
            onUpdatePlaybackPosition = { _, _ -> pA },
        )
        val networkMonitor = StubNetworkMonitor(initialIsOnline = true)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player, networkMonitor = networkMonitor)
        coordinator.playNow(pA)
        emitPlaying(player)
        assertTrue(coordinator.session.value is Active)
        networkMonitor.setOnline(online)
        val queueBefore = coordinator.queue.value
        val sessionBefore = coordinator.session.value
        val playerBefore = listOf(player.prepareCalls.toList(), player.seekCalls.toList(), player.speedCalls.toList())
        val countsBefore = listOf(player.playCallCount, player.stopCallCount)
        val updatesBefore = apiClient.updatePlaybackPositionCalls.toList()
        val fetchesBefore = apiClient.fetchPodcastCalls.toList()

        start(coordinator, listedB)

        assertEquals(queueBefore, coordinator.queue.value)
        assertEquals(sessionBefore, coordinator.session.value)
        assertEquals(playerBefore, listOf(player.prepareCalls, player.seekCalls, player.speedCalls))
        assertEquals(countsBefore, listOf(player.playCallCount, player.stopCallCount))
        assertEquals(updatesBefore, apiClient.updatePlaybackPositionCalls)
        assertEquals(expectedMessage, coordinator.notice.value?.message)
        if (!expectFetchOfB) assertEquals(fetchesBefore, apiClient.fetchPodcastCalls)
        assertSharedInv01(coordinator)
        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-test-08 shared-session-13 shared-dec-16 一覧の判定がGeneratingなら状態を変えず生成中を出す`() = runTest {
        assertManualStartRejectedKeepsState(podcast(id = "pB", status = "processing"), "生成中のため再生できません", expectFetchOfB = false)
    }

    @Test
    fun `android-test-08 shared-session-13 shared-dec-16 android-dec-06 一覧の判定がFailedなら状態を変えず生成の失敗の知らせを出す`() = runTest {
        assertManualStartRejectedKeepsState(podcast(id = "pB", status = "failed", errorMessage = "E"), "生成に失敗しました", expectFetchOfB = false)
    }

    @Test
    fun `android-test-08 shared-session-13 shared-dec-16 オフライン未キャッシュなら状態を変えずfetchもせずオフラインを出す`() = runTest {
        assertManualStartRejectedKeepsState(podcast(id = "pB"), "オフラインのため再生できません", expectFetchOfB = false, online = false)
    }

    @Test
    fun `android-test-08 shared-session-13 shared-dec-16 android-dec-08 取得がApiExceptionなら状態を変えず取得の失敗の知らせを出す`() = runTest {
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        assertManualStartRejectedKeepsState(podcast(id = "pB"), "オフラインです。接続を確認してから、もう一度お試しください", expectFetchOfB = true, fetchB = { throw exception })
    }

    @Test
    fun `android-test-08 shared-session-13 shared-dec-16 取り直した値がGeneratingなら状態を変えず生成中を出す`() = runTest {
        assertManualStartRejectedKeepsState(
            podcast(id = "pB"), "生成中のため再生できません", expectFetchOfB = true,
            fetchB = { podcast(id = "pB", status = "processing") },
        )
    }

    @Test
    fun `android-test-08 shared-session-13 shared-dec-16 android-dec-08 playでも取得失敗は状態を変えない`() = runTest {
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        assertManualStartRejectedKeepsState(
            podcast(id = "pB"), "オフラインです。接続を確認してから、もう一度お試しください", expectFetchOfB = true,
            fetchB = { throw exception }, start = { coordinator, episode -> coordinator.playNow(episode) },
        )
    }

    @Test
    fun `android-test-08 shared-session-13 shared-dec-16 NothingPlayingでaddToQueueした再生不可のepisodeはキューに残りsessionはNothingPlayingのまま`() = runTest {
        val pB = podcast(id = "pB", status = "processing")
        val player = FakePlayerController()
        val coordinator = newCoordinator(FakePodcastApiClient(), player)

        coordinator.addToQueue(pB)

        assertEquals(listOf(pB), coordinator.queue.value.items)
        assertTrue(coordinator.session.value is NothingPlaying)
        assertEquals("生成中のため再生できません", coordinator.notice.value?.message)
        assertTrue(player.prepareCalls.isEmpty())
    }

    @Test
    fun `android-test-08 android-dec-32 手動の開始が取得中に届いた完聴はmarkCompletedだけでplayerEndedもadvanceもしない`() = runTest {
        val (pA, pB, pC) = listOf("pA", "pB", "pC").map { podcast(id = it) }
        val fetchGate = CompletableDeferred<Unit>()
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id ->
                when (id) {
                    "pB" -> { fetchGate.await(); pB }
                    "pC" -> pC
                    else -> pA
                }
            },
            onUpdatePlaybackPosition = { _, _ -> pA },
        )
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.addToQueue(pB)
        coordinator.addToQueue(pC)

        launch { coordinator.playNow(pB) }
        runCurrent()
        emit(player, PlaybackState.Ended(300.0))

        assertEquals(listOf("pA"), apiClient.markCompletedCalls)
        assertEquals(starting(pA), coordinator.session.value) // playerEnded を呼んでいない
        assertEquals(pA, coordinator.queue.value.current)
        assertSharedInv01(coordinator)

        fetchGate.complete(Unit)
        runCurrent()

        assertEquals(starting(pB), coordinator.session.value)
        assertEquals(pB, coordinator.queue.value.current)
        assertTrue(apiClient.fetchPodcastCalls.none { it == "pC" })
        assertSharedInv01(coordinator)
        coordinator.stopForSubjectLeave()
    }

    // player の失敗と CACHED 経路の invalidate（android-contract-11 の Coordinator 部分）

    @Test
    fun `android-test-11 android-contract-11 CACHED再生がFailedのSourceかDecodeならinvalidateしてErroredのLoadedになり次回はNETWORK`() = runTest {
        for (reason in listOf(PlaybackFailureReason.Source, PlaybackFailureReason.Decode)) {
            val pA = podcast(id = "pA")
            val apiClient = FakePodcastApiClient(onFetchPodcast = { pA }, onUpdatePlaybackPosition = { _, _ -> pA })
            val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
            cacheManager.cache("u1", "pA", byteArrayOf(1))
            val player = FakePlayerController()
            val coordinator = newCoordinator(apiClient, player, cacheManager)
            coordinator.playNow(pA)
            assertEquals("file:///cache/audio/u1/pA.mp3", player.prepareCalls.last())

            emit(player, PlaybackState.Failed(reason))

            assertFalse("$reason", cacheManager.isCached("u1", "pA"))
            assertEquals(erroredLoaded(pA, reason), coordinator.session.value)
            assertNull(coordinator.nowPlaying.value)
            assertSharedInv01(coordinator)

            coordinator.retry() // キャッシュを捨てたので取得からやり直す

            assertEquals(listOf("pA"), apiClient.fetchPodcastCalls)
            assertEquals(playable(pA).audioUrl, player.prepareCalls.last())
            assertTrue(coordinator.session.value is Starting)
            coordinator.stopForSubjectLeave()
        }
    }

    @Test
    fun `android-test-11 android-contract-11 CACHED再生でもFailedのNetworkとUnknownではinvalidateしない`() = runTest {
        for (reason in listOf(PlaybackFailureReason.Network, PlaybackFailureReason.Unknown(code = 7))) {
            val pA = podcast(id = "pA")
            val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
            cacheManager.cache("u1", "pA", byteArrayOf(1))
            val player = FakePlayerController()
            val coordinator = newCoordinator(queueApiClient(pA), player, cacheManager)
            coordinator.playNow(pA)

            emit(player, PlaybackState.Failed(reason))

            assertTrue("$reason", cacheManager.isCached("u1", "pA"))
            assertEquals(erroredLoaded(pA, reason), coordinator.session.value)
            coordinator.stopForSubjectLeave()
        }
    }

    @Test
    fun `android-test-11 android-contract-11 NETWORK再生のFailedのSourceではキャッシュがあってもinvalidateしない`() = runTest {
        val pA = podcast(id = "pA")
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val player = FakePlayerController()
        val coordinator = newCoordinator(queueApiClient(pA), player, cacheManager)
        coordinator.playNow(pA)
        cacheManager.cache("u1", "pA", byteArrayOf(1)) // NETWORK で始めた後にキャッシュが現れた状況

        emit(player, PlaybackState.Failed(PlaybackFailureReason.Source))

        assertTrue(cacheManager.isCached("u1", "pA"))
        assertEquals(erroredLoaded(pA, PlaybackFailureReason.Source), coordinator.session.value)
        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-dec-69 Activeで再生エンジンが失敗するとPlayerFailedの知らせが出て次の開始の確定で消える`() = runTest {
        val pA = podcast(id = "pA")
        val player = FakePlayerController()
        val coordinator = newCoordinator(queueApiClient(pA), player)
        coordinator.playNow(pA)
        emitPlaying(player)
        assertTrue(coordinator.session.value is Active)
        assertNull(coordinator.notice.value) // 事象が来るまでは出ない

        emit(player, PlaybackState.Failed(PlaybackFailureReason.Source))

        assertEquals(erroredLoaded(pA, PlaybackFailureReason.Source), coordinator.session.value)
        assertEquals(PlaybackNotice.PlayerFailed, coordinator.notice.value)
        assertEquals("再生できませんでした。もう一度お試しください", coordinator.notice.value?.message)

        coordinator.retry() // 開始の確定で消える

        assertTrue(coordinator.session.value is Starting)
        assertNull(coordinator.notice.value)
        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-dec-69 Startingのうちに再生エンジンが失敗してもPlayerFailedの知らせが出る`() = runTest {
        val pA = podcast(id = "pA")
        val player = FakePlayerController()
        val coordinator = newCoordinator(queueApiClient(pA), player)
        coordinator.playNow(pA)
        assertTrue(coordinator.session.value is Starting)
        assertNull(coordinator.notice.value)

        emit(player, PlaybackState.Failed(PlaybackFailureReason.Network))

        assertTrue(coordinator.session.value is Errored)
        assertEquals(PlaybackNotice.PlayerFailed, coordinator.notice.value)
        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-dec-69 既にErroredのsessionへ届いたFailedはsessionを変えないので閉じた知らせを出し直さない`() = runTest {
        val pA = podcast(id = "pA")
        val player = FakePlayerController()
        val coordinator = newCoordinator(queueApiClient(pA), player)
        coordinator.playNow(pA)
        emitPlaying(player)
        emit(player, PlaybackState.Failed(PlaybackFailureReason.Source))
        assertEquals(PlaybackNotice.PlayerFailed, coordinator.notice.value)
        coordinator.dismissNotice()
        assertNull(coordinator.notice.value)

        emit(player, PlaybackState.Failed(PlaybackFailureReason.Network)) // Errored では handlePlayerFailure は何もしない

        assertEquals(erroredLoaded(pA, PlaybackFailureReason.Source), coordinator.session.value)
        assertNull(coordinator.notice.value)
        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-test-11 android-contract-11 invalidateがAudioCacheExceptionを投げてもErroredのPlayerへ進みその後のplayer事象も受け取る`() = runTest {
        val badId = podcast(id = "a.b") // "." を含む id は invalidate が InvalidId を投げる。isCached は検証しないので CACHED で始まる
        val fileSystem = FakeFileSystem()
        fileSystem.write("/cache/audio/u1/a.b.mp3", byteArrayOf(1))
        val player = FakePlayerController()
        val coordinator = newCoordinator(queueApiClient(badId), player, AudioCacheManager(fileSystem, baseDir = "/cache"))
        coordinator.playNow(badId)

        emit(player, PlaybackState.Failed(PlaybackFailureReason.Source))
        assertEquals(erroredLoaded(badId, PlaybackFailureReason.Source), coordinator.session.value)

        coordinator.retry() // 購読が生きていれば、この後の事象が session に届く
        emitPlaying(player)
        assertEquals(Active(playable(badId), 1.0), coordinator.session.value)
        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-test-11 android-contract-11 currentSubjectは開始の要求1回につき1回だけ呼ばれplayer事象では呼ばれない`() = runTest {
        val (pA, pB) = listOf("pA", "pB").map { podcast(id = it) }
        var subjectCalls = 0
        val player = FakePlayerController()
        val coordinator = newCoordinator(queueApiClient(pA, pB), player, currentSubject = { subjectCalls++; "user-1" })

        coordinator.playNow(pA)
        emit(player, PlaybackState.Failed(PlaybackFailureReason.Network))
        assertEquals(1, subjectCalls)

        coordinator.retry()
        assertEquals(2, subjectCalls)

        coordinator.playNow(pB)
        coordinator.stopForSubjectLeave()
        assertEquals(3, subjectCalls)
    }

    // player の事象の受け方（android-deriv-15 の本 PR の範囲）

    @Test
    fun `android-test-01 android-deriv-15 表に無い事象は無視されsessionは変わらず例外も出ない`() = runTest {
        val pA = podcast(id = "pA")
        val apiClient = queueApiClient(pA)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)

        emit(player, PlaybackState.Loading)
        assertEquals(starting(pA), coordinator.session.value)

        emit(player, PlaybackState.Paused(position = 0.0, duration = 300.0)) // Starting の列の Paused は playerStarted（位置は送らない）
        assertEquals(Active(playable(pA), 1.0), coordinator.session.value)
        assertTrue(apiClient.updatePlaybackPositionCalls.isEmpty())
        emitPlaying(player)
        emit(player, PlaybackState.Paused(position = 3.0, duration = 300.0)) // Active の列の Paused は、直前が Playing なら位置を 1 回送る
        assertEquals(Active(playable(pA), 1.0), coordinator.session.value)
        assertEquals(listOf("pA" to 3.0), apiClient.updatePlaybackPositionCalls)

        coordinator.stopForSubjectLeave()
        assertEquals(listOf("pA" to 3.0), apiClient.updatePlaybackPositionCalls)
        emit(player, PlaybackState.Failed(PlaybackFailureReason.Network)) // NothingPlaying への Failed
        assertTrue(coordinator.session.value is NothingPlaying)
    }

    // stopForSubjectLeave（shared-session-11・android-dec-45）

    @Test
    fun `android-test-26 shared-session-11 ErroredのIdOnlyからstopForSubjectLeaveしたら位置は送らずplayerを止めNothingPlayingになりキューは空になる`() = runTest {
        // 自動前進の開始前の失敗の後（session は Errored の IdOnly）。stopForSubjectLeave は session に依らず位置を送らない（shared-dec-09）。
        val (pA, pB) = listOf("pA", "pB").map { podcast(id = it) }
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id -> if (id == "pB") throw exception else pA },
            onUpdatePlaybackPosition = { _, _ -> pA },
        )
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.addToQueue(pB)
        emit(player, PlaybackState.Ended(300.0))
        assertEquals(erroredIdOnly("pB", SessionErrorReason.FetchFailed), coordinator.session.value)
        val updatesBefore = apiClient.updatePlaybackPositionCalls.toList()
        val stopsBefore = player.stopCallCount

        coordinator.stopForSubjectLeave()
        advanceTimeBy(30_000)
        runCurrent()

        assertTrue(coordinator.session.value is NothingPlaying)
        assertEquals(updatesBefore, apiClient.updatePlaybackPositionCalls)
        assertEquals(stopsBefore + 1, player.stopCallCount)
        assertNull(coordinator.queue.value.current)
        assertTrue(coordinator.queue.value.items.isEmpty())
        assertSharedInv01(coordinator)
    }

    @Test
    fun `android-test-26 shared-session-11 ErroredのLoadedからstopForSubjectLeaveしたら位置は送らずタイマーも止まりキューは空になる`() = runTest {
        // player の失敗の後（session は Errored の Loaded）。stopForSubjectLeave は session に依らず位置を送らない（shared-dec-09）。
        // Failed で timer が止まることも、30 秒進めても送られないことで確かめる。
        val pA = podcast(id = "pA")
        val apiClient = queueApiClient(pA)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        emitPlaying(player)
        emit(player, PlaybackState.Failed(PlaybackFailureReason.Network))
        assertEquals(erroredLoaded(pA, PlaybackFailureReason.Network), coordinator.session.value)
        player.setPosition(33.0)
        val stopsBefore = player.stopCallCount

        coordinator.stopForSubjectLeave()
        advanceTimeBy(30_000) // Failed で止めないと timer が孤児になる
        runCurrent()

        assertTrue(coordinator.session.value is NothingPlaying)
        assertTrue(apiClient.updatePlaybackPositionCalls.isEmpty())
        assertEquals(stopsBefore + 1, player.stopCallCount)
        assertTrue(coordinator.queue.value.items.isEmpty())
        assertNull(coordinator.queue.value.current)
        assertSharedInv01(coordinator)
    }

    // 11 辺を Coordinator 経由で起こす（android-contract-01・shared-session-04）

    @Test
    fun `android-test-01 shared-session-04 開始から完聴を経て自動前進と末尾の停止までの辺がsessionに現れる`() = runTest {
        val (pA, pB) = listOf("pA", "pB").map { podcast(id = it) }
        val markGate = CompletableDeferred<Unit>()
        val fetchGate = CompletableDeferred<Unit>()
        val byId = listOf(pA, pB).associateBy { it.id }
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id ->
                if (id == "pB") fetchGate.await()
                byId.getValue(id)
            },
            onUpdatePlaybackPosition = { id, _ -> byId.getValue(id) },
            onMarkCompleted = { markGate.await() },
        )
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        assertTrue(coordinator.session.value is NothingPlaying)

        coordinator.playNow(pA) // NothingPlaying から Starting
        coordinator.addToQueue(pB)
        assertEquals(starting(pA), coordinator.session.value)
        assertSharedInv01(coordinator)

        emitPlaying(player) // Starting から Active
        assertEquals(Active(playable(pA), 1.0), coordinator.session.value)
        assertSharedInv01(coordinator)

        emit(player, PlaybackState.Ended(300.0)) // Active から Completed（pB の取得と完聴の記録を保留している間に観測する）
        assertEquals(Completed(playable(pA)), coordinator.session.value)
        assertEquals(pA, coordinator.queue.value.current)
        assertSharedInv01(coordinator)

        fetchGate.complete(Unit) // Completed から次の Starting（advance。markCompleted の応答は待たない）
        runCurrent()
        assertEquals(starting(pB), coordinator.session.value)
        assertEquals(pB, coordinator.queue.value.current)
        assertSharedInv01(coordinator)

        emit(player, PlaybackState.Loading)
        emitPlaying(player)
        emit(player, PlaybackState.Ended(300.0)) // 末尾: Active から Completed を経て Stopped
        assertEquals(Stopped(playable(pB)), coordinator.session.value)
        assertEquals(playable(pB).id, coordinator.nowPlaying.value?.episodeId)
        assertSharedInv01(coordinator)

        coordinator.playNow(pA) // Stopped から Starting
        assertEquals(starting(pA), coordinator.session.value)
        assertSharedInv01(coordinator)

        markGate.complete(Unit)
        runCurrent()
        assertEquals(listOf("pA", "pB"), apiClient.markCompletedCalls)
        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-test-01 shared-session-04 Activeからの手動の開始とStartingからの手動の開始がStartingになる`() = runTest {
        val (pA, pB, pC) = listOf("pA", "pB", "pC").map { podcast(id = it) }
        val player = FakePlayerController()
        val coordinator = newCoordinator(queueApiClient(pA, pB, pC), player)
        coordinator.playNow(pA)
        emitPlaying(player)
        assertTrue(coordinator.session.value is Active)

        coordinator.playNow(pB) // Active から playNow の辺
        assertEquals(starting(pB), coordinator.session.value)
        assertSharedInv01(coordinator)

        coordinator.playNow(pC) // Starting の割り込みは stop の辺を経て start
        assertEquals(starting(pC), coordinator.session.value)
        assertEquals(pC, coordinator.queue.value.current)
        assertSharedInv01(coordinator)
        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-test-01 shared-session-04 失敗の辺とretryとErroredからの手動の開始がsessionに現れる`() = runTest {
        val (pA, pB) = listOf("pA", "pB").map { podcast(id = it) }
        val player = FakePlayerController()
        val coordinator = newCoordinator(queueApiClient(pA, pB), player)
        coordinator.playNow(pA)

        emit(player, PlaybackState.Failed(PlaybackFailureReason.Network)) // Starting から Errored
        assertEquals(erroredLoaded(pA, PlaybackFailureReason.Network), coordinator.session.value)
        assertSharedInv01(coordinator)

        coordinator.retry() // Errored から Starting（retry）
        assertEquals(starting(pA), coordinator.session.value)

        emitPlaying(player)
        emit(player, PlaybackState.Failed(PlaybackFailureReason.Decode)) // Active から Errored
        assertEquals(erroredLoaded(pA, PlaybackFailureReason.Decode), coordinator.session.value)
        assertSharedInv01(coordinator)

        coordinator.playNow(pB) // Errored から Starting（start）
        assertEquals(starting(pB), coordinator.session.value)
        assertEquals(pB, coordinator.queue.value.current)
        assertSharedInv01(coordinator)
        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-test-01 shared-session-04 retryはErrored以外では何もしない`() = runTest {
        val pA = podcast(id = "pA")
        val apiClient = queueApiClient(pA)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.retry() // NothingPlaying
        assertTrue(coordinator.session.value is NothingPlaying)
        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
        assertTrue(player.prepareCalls.isEmpty())

        coordinator.playNow(pA)
        emitPlaying(player)
        val activeBefore = coordinator.session.value
        coordinator.retry() // Active
        assertEquals(activeBefore, coordinator.session.value)
        assertEquals(listOf("pA"), apiClient.fetchPodcastCalls)
        assertEquals(1, player.prepareCalls.size)
        assertEquals(0, player.stopCallCount)
        coordinator.stopForSubjectLeave()
    }

    // 旧経路の完聴は player の事象を注入しなくても進む（android-dec-31）

    @Test
    fun `android-test-01 android-dec-31 Startingのまま完聴しても例外なく次のepisodeのStartingへ進む`() = runTest {
        val (pA, pB) = listOf("pA", "pB").map { podcast(id = it) }
        val apiClient = queueApiClient(pA, pB)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.addToQueue(pB)

        emit(player, PlaybackState.Ended(300.0))

        assertEquals(listOf("pA"), apiClient.markCompletedCalls)
        assertEquals(starting(pB), coordinator.session.value)
        assertEquals(pB, coordinator.queue.value.current)
        assertSharedInv01(coordinator)
        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-test-01 android-dec-31 Startingのまま末尾で完聴しても例外なくStoppedになりepisodeを保持する`() = runTest {
        val pA = podcast(id = "pA")
        val apiClient = queueApiClient(pA)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)

        emit(player, PlaybackState.Ended(300.0))

        assertEquals(Stopped(playable(pA)), coordinator.session.value)
        assertEquals(pA.id, coordinator.nowPlaying.value?.episodeId)
        assertEquals(listOf("pA"), apiClient.markCompletedCalls)
        assertSharedInv01(coordinator)
    }

    @Test
    fun `android-test-01 android-dec-31 再生していない状態の完聴の通知は何もしない`() = runTest {
        val pA = podcast(id = "pA")
        val apiClient = queueApiClient(pA)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.stopForSubjectLeave()
        val callsBefore = listOf(apiClient.markCompletedCalls.toList(), apiClient.fetchPodcastCalls.toList())

        emit(player, PlaybackState.Ended(300.0))

        assertTrue(coordinator.session.value is NothingPlaying)
        assertEquals(callsBefore, listOf(apiClient.markCompletedCalls, apiClient.fetchPodcastCalls))
    }

    // 何も再生していないときだけ即再生する（android-deriv-02）と、キュー操作の後の shared-inv-01

    @Test
    fun `android-deriv-02 Stoppedの間のaddToQueueは即再生しない`() = runTest {
        val (pA, pC) = listOf("pA", "pC").map { podcast(id = it) }
        val player = FakePlayerController()
        val coordinator = newCoordinator(queueApiClient(pA, pC), player)
        coordinator.playNow(pA)
        emit(player, PlaybackState.Ended(300.0))
        val preparesBefore = player.prepareCalls.size

        coordinator.addToQueue(pC)

        assertEquals(preparesBefore, player.prepareCalls.size)
        assertEquals(listOf(pA, pC), coordinator.queue.value.items)
        assertEquals(Stopped(playable(pA)), coordinator.session.value)
        assertSharedInv01(coordinator)
    }

    @Test
    fun `shared-session-04 moveUpNextと待機列のremoveFromQueueの後もsessionとキューの現在位置が一致する`() = runTest {
        // removeFromQueue で現在の id を渡す経路は、既存の挙動（キューだけ更新）を保つため対象外。
        val (pA, pB, pC) = listOf("pA", "pB", "pC").map { podcast(id = it) }
        val coordinator = newCoordinator(queueApiClient(pA, pB, pC), FakePlayerController())
        coordinator.playNow(pA)
        coordinator.playNext(pB)
        coordinator.playNext(pC)

        coordinator.moveUp(pB.id)
        assertSharedInv01(coordinator)
        coordinator.removeFromQueue(pC.id)

        assertSharedInv01(coordinator)
        assertEquals(pA, coordinator.queue.value.current)
        coordinator.stopForSubjectLeave()
    }

    // --- id を受ける command（android-dec-64: 未知の id は何もしない。moveUp・moveDown は id で位置を引く） ---

    @Test
    fun `android-dec-64 未知のidのplayNowはsessionもキューもplayerも通信も変えない`() = runTest {
        val apiClient = FakePodcastApiClient()
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.playNow("unknown")

        assertEquals(NothingPlaying, coordinator.session.value)
        assertTrue(coordinator.queue.value.items.isEmpty())
        assertNull(coordinator.notice.value)
        assertNull(coordinator.nowPlaying.value)
        assertTrue(player.prepareCalls.isEmpty())
        assertEquals(0, apiClient.fetchPodcastsCallCount)
        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
    }

    @Test
    fun `android-dec-64 未知のidのplayNextとaddToQueueはキューもsessionも通信も変えず既知のidでは積まれる`() = runTest {
        val pA = podcast(id = "pA")
        val pB = podcast(id = "pB")
        val apiClient = queueApiClient(pA, pB)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)

        coordinator.playNext("unknown")
        coordinator.addToQueue("unknown")

        assertEquals(NothingPlaying, coordinator.session.value)
        assertTrue(coordinator.queue.value.items.isEmpty())
        assertNull(coordinator.notice.value)
        assertTrue(apiClient.fetchPodcastCalls.isEmpty())

        // 正方向の対照: 既知の id は NothingPlaying なら開始し、続く addToQueue は待機列へ積む。
        coordinator.addToQueue(pA)
        coordinator.addToQueue(pB)
        assertEquals(pA, coordinator.queue.value.current)
        assertEquals(listOf(pB), coordinator.queue.value.upNext)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-dec-64 未知のidのcommandは待っている自動の前進を捨てない`() = runTest {
        // 世代を解決の前に進める誤った実装では、取得を待つ前進が捨てられて Completed(pA) のままになる。
        val (pA, pC) = listOf("pA", "pC").map { podcast(id = it) }
        val gateC = CompletableDeferred<Unit>()
        val apiClient = gatedApiClient(listOf(pA, pC), fetchGates = mapOf("pC" to gateC))
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.addToQueue(pC)
        emitPlaying(player)

        emit(player, PlaybackState.Ended(300.0)) // 自動の前進が pC の取得で待たされる
        assertEquals(Completed(playable(pA)), coordinator.session.value)

        coordinator.playNow("unknown")
        coordinator.playNext("unknown")
        coordinator.addToQueue("unknown")
        gateC.complete(Unit)
        runCurrent()

        assertEquals(starting(pC), coordinator.session.value)
        assertEquals(listOf(playable(pA).audioUrl, playable(pC).audioUrl), player.prepareCalls)
        assertEquals(listOf("pA", "pC"), coordinator.queue.value.items.map { it.id })

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `moveUpとmoveDownはidの待機列の位置を一つ前後へ動かす`() = runTest {
        val (p, a, b, c) = listOf("p", "a", "b", "c").map { podcast(id = it) }
        val coordinator = newCoordinator(queueApiClient(p, a, b, c), FakePlayerController())
        coordinator.playNow(p)
        coordinator.addToQueue(a)
        coordinator.addToQueue(b)
        coordinator.addToQueue(c)

        coordinator.moveUp(b.id)
        assertEquals(listOf("b", "a", "c"), coordinator.queue.value.upNext.map { it.id })

        coordinator.moveDown(b.id)
        assertEquals(listOf("a", "b", "c"), coordinator.queue.value.upNext.map { it.id })

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `moveUpとmoveDownは先頭と末尾と待機列に無いidと現在再生中のidでキューを変えない`() = runTest {
        val (p, a, b) = listOf("p", "a", "b").map { podcast(id = it) }
        val coordinator = newCoordinator(queueApiClient(p, a, b), FakePlayerController())
        coordinator.playNow(p)
        coordinator.addToQueue(a)
        coordinator.addToQueue(b)
        val before = coordinator.queue.value

        coordinator.moveUp(a.id)
        coordinator.moveDown(b.id)
        coordinator.moveUp("x")
        coordinator.moveDown("x")
        coordinator.moveUp(p.id)
        coordinator.moveDown(p.id)

        assertEquals(before, coordinator.queue.value)
        assertEquals(listOf("a", "b"), coordinator.queue.value.upNext.map { it.id })

        coordinator.stopForSubjectLeave()
    }

    // nowPlaying は session の派生

    @Test
    fun `nowPlayingはEpisodePlayableの7fieldをそのまま同じ参照で持つ`() = runTest {
        val pA = playableWithContent("pA")
        val coordinator = newCoordinator(queueApiClient(pA), FakePlayerController())

        coordinator.playNow(pA)

        val nowPlaying = coordinator.nowPlaying.value
        assertNotNull(nowPlaying)
        assertEquals(pA.id, nowPlaying!!.episodeId)
        assertEquals(pA.displayTitle, nowPlaying.displayTitle)
        assertEquals(pA.japaneseIntroText, nowPlaying.japaneseIntroText)
        assertSame(pA.transcript, nowPlaying.segments)
        assertSame(pA.glossary, nowPlaying.vocabulary)
        assertSame(pA.quiz, nowPlaying.quiz)
        assertEquals(pA.difficultyCode, nowPlaying.difficulty)
        coordinator.stopForSubjectLeave()
    }

    // --- 連続した開始の切り替え（android-contract-22。baseline: 実装前の現行挙動で green） ---

    @Test
    fun `android-test-24 連続playNowの切り替えで前のidの最終同期は1回出てその後の周期送信は最後のepisodeだけ`() = runTest {
        // verifies: android-contract-22 / android-test-24
        val pA = podcast(id = "pA")
        val pB = podcast(id = "pB")
        val apiClient = queueApiClient(pA, pB)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        emitPlaying(player)
        player.setPosition(20.0)

        coordinator.playNow(pB)
        emit(player, PlaybackState.Loading)
        emitPlaying(player)

        assertEquals(listOf("pA" to 20.0), apiClient.updatePlaybackPositionCalls)

        apiClient.updatePlaybackPositionCalls.clear()
        player.setPosition(5.0)
        advanceTimeBy(15_000)
        runCurrent()

        assertEquals(listOf("pB" to 5.0), apiClient.updatePlaybackPositionCalls)

        coordinator.stopForSubjectLeave()
    }

    // --- 完聴の順序・位置同期の送信条件・主体の離脱（android-slice-playback-04） ---

    /** fetchPodcast・updatePlaybackPosition・markCompleted を、指定したものだけ保留できる Fake。 */
    private fun gatedApiClient(
        episodes: List<Episode>,
        fetchGates: Map<String, CompletableDeferred<Unit>> = emptyMap(),
        updateGate: CompletableDeferred<Unit>? = null,
        markGate: CompletableDeferred<Unit>? = null,
    ): FakePodcastApiClient {
        val byId = episodes.associateBy { it.id }
        return FakePodcastApiClient(
            onFetchPodcast = { id ->
                fetchGates[id]?.await()
                byId.getValue(id)
            },
            onUpdatePlaybackPosition = { id, _ ->
                updateGate?.await()
                byId.getValue(id)
            },
            onMarkCompleted = { markGate?.await() },
        )
    }

    // 完聴の順序（shared-session-07・android-contract-10）

    @Test
    fun `android-test-10 shared-session-07 完聴でmarkCompletedの後に総時間を1回送る`() = runTest {
        // verifies: android-contract-10 / android-test-10
        val p1 = podcast(id = "p1")
        val markGate = CompletableDeferred<Unit>()
        val apiClient = gatedApiClient(listOf(p1), markGate = markGate)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(p1)
        emitPlaying(player)

        emit(player, PlaybackState.Ended(300.0))

        assertEquals(listOf("p1"), apiClient.markCompletedCalls)
        assertTrue(apiClient.updatePlaybackPositionCalls.isEmpty())
        markGate.complete(Unit)
        runCurrent()
        assertEquals(listOf("p1" to 300.0), apiClient.updatePlaybackPositionCalls)
    }

    @Test
    fun `android-test-10 shared-session-07 同じセッションで2回目のEndedは無視される`() = runTest {
        // verifies: android-contract-10 / android-test-10
        val p1 = podcast(id = "p1")
        val apiClient = gatedApiClient(listOf(p1))
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(p1)
        emitPlaying(player)
        emit(player, PlaybackState.Ended(300.0))
        val sessionBefore = coordinator.session.value
        val updatesBefore = apiClient.updatePlaybackPositionCalls.toList()

        emit(player, PlaybackState.Ended(301.0))

        assertEquals(listOf("p1"), apiClient.markCompletedCalls)
        assertEquals(sessionBefore, coordinator.session.value)
        assertEquals(updatesBefore, apiClient.updatePlaybackPositionCalls)
    }

    @Test
    fun `android-test-10 shared-session-07 Endedの総時間がnullならEpisodeの総時間を送る`() = runTest {
        // verifies: android-contract-10 / android-test-10
        val p1 = podcast(id = "p1")
        val apiClient = gatedApiClient(listOf(p1))
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(p1)
        emitPlaying(player)

        emit(player, PlaybackState.Ended(null))

        assertEquals(listOf("p1" to 300.0), apiClient.updatePlaybackPositionCalls)
    }

    @Test
    fun `android-test-10 shared-session-07 総時間が不明ならEnded時点の位置を送る`() = runTest {
        // verifies: android-contract-10 / android-test-10
        val p1 = podcast(id = "p1", durationSeconds = 0)
        val apiClient = gatedApiClient(listOf(p1))
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(p1)
        emitPlaying(player)
        player.setPosition(42.0)

        emit(player, PlaybackState.Ended(null))

        assertEquals(listOf("p1" to 42.0), apiClient.updatePlaybackPositionCalls)
    }

    @Test
    fun `android-test-10 shared-session-07 位置も0なら位置を送らずmarkCompletedだけ`() = runTest {
        // verifies: android-contract-10 / android-test-10
        val p1 = podcast(id = "p1", durationSeconds = 0)
        val apiClient = gatedApiClient(listOf(p1))
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(p1)
        emitPlaying(player)

        emit(player, PlaybackState.Ended(null))

        assertEquals(listOf("p1"), apiClient.markCompletedCalls)
        assertTrue(apiClient.updatePlaybackPositionCalls.isEmpty())
    }

    @Test
    fun `android-test-10 shared-session-07 shared-dec-15 markCompletedの応答を待たずに次のepisodeがStartingになる`() = runTest {
        // verifies: android-contract-10 / android-test-10
        val (pA, pB) = listOf("pA", "pB").map { podcast(id = it) }
        val markGate = CompletableDeferred<Unit>()
        val apiClient = gatedApiClient(listOf(pA, pB), markGate = markGate)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.addToQueue(pB)
        emitPlaying(player)

        emit(player, PlaybackState.Ended(300.0))

        assertEquals(listOf("pA"), apiClient.markCompletedCalls) // markGate は閉じたまま
        assertEquals(starting(pB), coordinator.session.value)
        assertEquals(pB, coordinator.queue.value.current)
        assertSharedInv01(coordinator)
        markGate.complete(Unit)
        runCurrent()
        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-test-10 shared-session-07 送信の途中でplayerが位置を0に戻しても送る値はEnded時点の値`() = runTest {
        // verifies: android-contract-10 / android-test-10
        val pA = podcast(id = "pA", durationSeconds = 0)
        val pB = podcast(id = "pB")
        val markGate = CompletableDeferred<Unit>()
        val apiClient = gatedApiClient(listOf(pA, pB), markGate = markGate)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.addToQueue(pB)
        emitPlaying(player)
        player.setPosition(120.0)

        emit(player, PlaybackState.Ended(null)) // 次の開始が player.stop() で位置を 0 に戻す
        markGate.complete(Unit)
        runCurrent()

        assertEquals(listOf("pA" to 120.0), apiClient.updatePlaybackPositionCalls)
        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-test-10 shared-session-04 前進の取得を待つ間に次に再生へ足したepisodeは次のepisodeの後に残る`() = runTest {
        // verifies: android-contract-10 / android-test-10
        val (pA, pB, pX) = listOf("pA", "pB", "pX").map { podcast(id = it) }
        val fetchGate = CompletableDeferred<Unit>()
        val apiClient = gatedApiClient(listOf(pA, pB, pX), fetchGates = mapOf("pB" to fetchGate))
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.addToQueue(pB)
        emitPlaying(player)
        emit(player, PlaybackState.Ended(300.0))
        assertEquals(Completed(playable(pA)), coordinator.session.value)
        assertEquals(pA, coordinator.queue.value.current) // 取得を待つ間は完聴した episode のまま
        assertSharedInv01(coordinator)

        coordinator.playNext(pX)
        fetchGate.complete(Unit)
        runCurrent()

        assertEquals(pB, coordinator.queue.value.current)
        assertEquals(listOf(pX), coordinator.queue.value.upNext)
        assertEquals(starting(pB), coordinator.session.value)
        assertSharedInv01(coordinator)
        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-test-10 shared-session-07 android-dec-38 手動の開始の取得中に届いた完聴が2回でもmarkCompletedは1回でタイマーも止まる`() = runTest {
        // verifies: android-contract-10 / android-test-10
        val (pA, pB) = listOf("pA", "pB").map { podcast(id = it) }
        val fetchGate = CompletableDeferred<Unit>()
        val apiClient = gatedApiClient(listOf(pA, pB), fetchGates = mapOf("pB" to fetchGate))
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.addToQueue(pB)
        emitPlaying(player) // Active。周期の timer が動く
        launch { coordinator.playNow(pB) }
        runCurrent() // pB の取得で止まり、playMutex を保持している

        emit(player, PlaybackState.Ended(300.0))
        emit(player, PlaybackState.Ended(301.0))
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(listOf("pA"), apiClient.markCompletedCalls)
        assertTrue(apiClient.updatePlaybackPositionCalls.isEmpty()) // timer は止まり、完聴の位置も送らない
        assertEquals(Active(playable(pA), 1.0), coordinator.session.value)
        assertEquals(pA, coordinator.queue.value.current)

        fetchGate.complete(Unit)
        runCurrent()
        assertEquals(starting(pB), coordinator.session.value)
        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-test-10 shared-session-07 android-dec-38 手動の開始の取得中に完聴の後でplayerが始まっても2回目の完聴でmarkCompletedを重ねない`() = runTest {
        // verifies: android-contract-10 / android-test-10
        val (pA, pB) = listOf("pA", "pB").map { podcast(id = it) }
        val fetchGate = CompletableDeferred<Unit>()
        val apiClient = gatedApiClient(listOf(pA, pB), fetchGates = mapOf("pB" to fetchGate))
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA) // Starting のまま（Playing を入れない）
        coordinator.addToQueue(pB)
        launch { coordinator.playNow(pB) }
        runCurrent() // pB の取得で止まり、playMutex を保持している

        emit(player, PlaybackState.Ended(300.0))
        emit(player, PlaybackState.Paused(position = 0.0, duration = 300.0)) // playerStarted で Active になる
        emit(player, PlaybackState.Ended(301.0))

        assertEquals(listOf("pA"), apiClient.markCompletedCalls)
        assertEquals(Active(playable(pA), 1.0), coordinator.session.value)
        assertEquals(pA, coordinator.queue.value.current)

        fetchGate.complete(Unit)
        runCurrent()
        assertEquals(starting(pB), coordinator.session.value)
        coordinator.stopForSubjectLeave()
    }

    // 位置同期の送信条件（shared-session-05・android-contract-21 / android-test-21）

    @Test
    fun `android-test-21 shared-session-05 ErroredのFetchFailedの後は送らない`() = runTest {
        // verifies: android-contract-21 / android-test-21
        val (pA, pB) = listOf("pA", "pB").map { podcast(id = it) }
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id -> if (id == "pB") throw exception else pA },
            onUpdatePlaybackPosition = { _, _ -> pA },
        )
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.addToQueue(pB)
        emitPlaying(player)
        emit(player, PlaybackState.Ended(300.0))
        assertEquals(erroredIdOnly("pB", SessionErrorReason.FetchFailed), coordinator.session.value)
        val updatesBefore = apiClient.updatePlaybackPositionCalls.toList()

        advanceTimeBy(30_000)
        runCurrent()
        coordinator.stopForSubjectLeave()

        assertEquals(updatesBefore, apiClient.updatePlaybackPositionCalls)
    }

    @Test
    fun `android-test-21 shared-session-05 ErroredのLoadedとStoppedとNothingPlayingからstopForSubjectLeaveしても送らない`() = runTest {
        // verifies: android-contract-21 / android-test-21
        run { // Errored の Loaded
            val pA = podcast(id = "pA")
            val apiClient = queueApiClient(pA)
            val player = FakePlayerController()
            val coordinator = newCoordinator(apiClient, player)
            coordinator.playNow(pA)
            emitPlaying(player)
            emit(player, PlaybackState.Failed(PlaybackFailureReason.Network))
            assertEquals(erroredLoaded(pA, PlaybackFailureReason.Network), coordinator.session.value)
            val updatesBefore = apiClient.updatePlaybackPositionCalls.toList()
            player.setPosition(50.0)

            coordinator.stopForSubjectLeave()

            assertEquals(updatesBefore, apiClient.updatePlaybackPositionCalls)
        }
        run { // Stopped（末尾の完聴の後）
            val pA = podcast(id = "pA")
            val apiClient = queueApiClient(pA)
            val player = FakePlayerController()
            val coordinator = newCoordinator(apiClient, player)
            coordinator.playNow(pA)
            emitPlaying(player)
            emit(player, PlaybackState.Ended(300.0))
            assertEquals(Stopped(playable(pA)), coordinator.session.value)
            val updatesBefore = apiClient.updatePlaybackPositionCalls.toList()
            player.setPosition(50.0)

            coordinator.stopForSubjectLeave()

            assertEquals(updatesBefore, apiClient.updatePlaybackPositionCalls)
        }
        run { // NothingPlaying
            val apiClient = FakePodcastApiClient()
            val player = FakePlayerController()
            val coordinator = newCoordinator(apiClient, player)
            player.setPosition(50.0)

            coordinator.stopForSubjectLeave()

            assertTrue(apiClient.updatePlaybackPositionCalls.isEmpty())
        }
    }

    @Test
    fun `android-test-21 shared-session-05 Failedを受けたらタイマーが止まる`() = runTest {
        // verifies: android-contract-21 / android-test-21
        val pA = podcast(id = "pA")
        val apiClient = queueApiClient(pA)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        emitPlaying(player)
        player.setPosition(10.0)

        emit(player, PlaybackState.Failed(PlaybackFailureReason.Network))
        advanceTimeBy(30_000)
        runCurrent()

        assertTrue(apiClient.updatePlaybackPositionCalls.isEmpty())
    }

    // 周期の送信と一時停止（shared-session-06・android-contract-21 / android-test-22）

    @Test
    fun `android-test-22 shared-session-06 Playingへ戻ると15秒の周期が張り直される`() = runTest {
        // verifies: android-contract-21 / android-test-22
        val p1 = podcast(id = "p1")
        val apiClient = gatedApiClient(listOf(p1))
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(p1)
        emitPlaying(player)
        coordinator.togglePlayPause()
        emit(player, PlaybackState.Paused(position = 7.0, duration = 300.0))

        emitPlaying(player)
        player.setPosition(9.0)
        advanceTimeBy(15_000)
        runCurrent()

        assertEquals(listOf("p1" to 7.0, "p1" to 9.0), apiClient.updatePlaybackPositionCalls)
        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-test-22 shared-session-06 StartingのPausedは送らない`() = runTest {
        // verifies: android-contract-21 / android-test-22
        val p1 = podcast(id = "p1")
        val apiClient = gatedApiClient(listOf(p1))
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(p1) // Starting

        emit(player, PlaybackState.Paused(position = 5.0, duration = 300.0))
        advanceTimeBy(15_000)
        runCurrent()

        assertTrue(apiClient.updatePlaybackPositionCalls.isEmpty())
        assertTrue(coordinator.session.value is Active)
    }

    @Test
    fun `android-test-22 shared-session-06 Pausedが続いても2回目は送らない`() = runTest {
        // verifies: android-contract-21 / android-test-22
        val p1 = podcast(id = "p1")
        val apiClient = gatedApiClient(listOf(p1))
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(p1)
        emitPlaying(player)
        emit(player, PlaybackState.Paused(position = 7.0, duration = 300.0))

        emit(player, PlaybackState.Paused(position = 8.0, duration = 300.0))

        assertEquals(listOf("p1" to 7.0), apiClient.updatePlaybackPositionCalls)
        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-test-22 shared-session-06 読込を挟んだ一時停止は送らないがタイマーは止まる`() = runTest {
        // verifies: android-contract-21 / android-test-22
        val p1 = podcast(id = "p1")
        val apiClient = gatedApiClient(listOf(p1))
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(p1)
        emitPlaying(player)
        emit(player, PlaybackState.Loading)

        emit(player, PlaybackState.Paused(position = 7.0, duration = 300.0))
        player.setPosition(9.0)
        advanceTimeBy(15_000)
        runCurrent()

        assertTrue(apiClient.updatePlaybackPositionCalls.isEmpty())
    }

    @Test
    fun `android-test-22 shared-session-06 再生中の読込ではタイマーを止めない`() = runTest {
        // verifies: android-contract-21 / android-test-22
        val p1 = podcast(id = "p1")
        val apiClient = gatedApiClient(listOf(p1))
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(p1)
        emitPlaying(player)

        emit(player, PlaybackState.Loading)
        player.setPosition(4.0)
        advanceTimeBy(15_000)
        runCurrent()

        assertEquals(listOf("p1" to 4.0), apiClient.updatePlaybackPositionCalls)
        coordinator.stopForSubjectLeave()
    }

    // 停止直前の位置は Active からだけ送る（android-deriv-16 の (a)(b) / android-test-23）

    @Test
    fun `android-test-23 android-deriv-16 Startingからの切り替えと停止は送らない`() = runTest {
        // verifies: android-contract-21 / android-test-23
        val (pA, pB) = listOf("pA", "pB").map { podcast(id = it) }
        val apiClient = queueApiClient(pA, pB)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA) // Starting
        player.setPosition(30.0)

        coordinator.playNow(pB)
        player.setPosition(30.0)
        coordinator.stopForSubjectLeave()

        assertTrue(apiClient.updatePlaybackPositionCalls.isEmpty())
    }

    @Test
    fun `android-test-23 android-deriv-16 Completedからの前進の確定もその後の手動の切り替えも完聴の送信のほかは送らない`() = runTest {
        // verifies: android-contract-21 / android-test-23
        val (pA, pB, pC) = listOf("pA", "pB", "pC").map { podcast(id = it) }
        val fetchGate = CompletableDeferred<Unit>()
        val apiClient = gatedApiClient(listOf(pA, pB, pC), fetchGates = mapOf("pB" to fetchGate))
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.addToQueue(pB)
        emitPlaying(player)
        emit(player, PlaybackState.Ended(300.0))
        assertEquals(Completed(playable(pA)), coordinator.session.value)

        launch { coordinator.playNow(pC) }
        fetchGate.complete(Unit)
        runCurrent()

        assertEquals(starting(pC), coordinator.session.value)
        assertEquals(listOf("pA" to 300.0), apiClient.updatePlaybackPositionCalls)
        coordinator.stopForSubjectLeave()
    }

    // 手動の切り替えのキューの書込は、前の再生の最終同期より前（shared-session-04）

    @Test
    fun `android-test-01 shared-session-04 手動の切り替えで前の再生の最終同期を待つ間に次に再生へ足したepisodeは切り替え先の次に残る`() = runTest {
        val (pA, pB, pX) = listOf("pA", "pB", "pX").map { podcast(id = it) }
        val updateGate = CompletableDeferred<Unit>()
        val apiClient = gatedApiClient(listOf(pA, pB), updateGate = updateGate)
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        cacheManager.cache("u1", "pB", byteArrayOf(1)) // 取得を経ない
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player, cacheManager)
        coordinator.playNow(pA)
        coordinator.addToQueue(pB)
        emitPlaying(player)

        launch { coordinator.playNow(pB) }
        runCurrent() // 前の再生（Active の pA）の最終同期の送信で止まる
        coordinator.playNext(pX)
        updateGate.complete(Unit)
        runCurrent()

        assertEquals(listOf("pA", "pB", "pX"), coordinator.queue.value.items.map { it.id })
        assertEquals(pB, coordinator.queue.value.current)
        assertEquals(starting(pB), coordinator.session.value)
        assertSharedInv01(coordinator)
        coordinator.stopForSubjectLeave()
    }

    // 主体の離脱での停止（shared-leave-01 / android-test-27）

    @Test
    fun `android-test-27 shared-leave-01 ActiveからstopForSubjectLeaveすると位置を送らずplayerを止めキューを空にする`() = runTest {
        // verifies: shared-leave-01 / android-test-27
        val (pA, pB) = listOf("pA", "pB").map { podcast(id = it) }
        val apiClient = queueApiClient(pA, pB)
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.addToQueue(pB)
        emitPlaying(player)
        player.setPosition(42.0)
        val updatesBefore = apiClient.updatePlaybackPositionCalls.toList()
        val stopsBefore = player.stopCallCount

        coordinator.stopForSubjectLeave()
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(stopsBefore + 1, player.stopCallCount)
        assertTrue(coordinator.session.value is NothingPlaying)
        assertNull(coordinator.nowPlaying.value)
        assertTrue(coordinator.queue.value.items.isEmpty())
        assertNull(coordinator.queue.value.current)
        assertEquals(updatesBefore, apiClient.updatePlaybackPositionCalls)
    }

    @Test
    fun `android-test-27 shared-leave-01 取得中の手動の開始は離脱の後に再生しない`() = runTest {
        // verifies: shared-leave-01 / android-test-27
        val pA = podcast(id = "pA")
        val fetchGate = CompletableDeferred<Unit>()
        val apiClient = gatedApiClient(listOf(pA), fetchGates = mapOf("pA" to fetchGate))
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        launch { coordinator.playNow(pA) }
        runCurrent() // pA の取得で止まる

        coordinator.stopForSubjectLeave()
        fetchGate.complete(Unit)
        runCurrent()

        assertTrue(player.prepareCalls.isEmpty())
        assertEquals(0, player.playCallCount)
        assertTrue(coordinator.session.value is NothingPlaying)
        assertTrue(coordinator.queue.value.items.isEmpty())
        assertTrue(apiClient.updatePlaybackPositionCalls.isEmpty())
        assertTrue(apiClient.markCompletedCalls.isEmpty())
    }

    @Test
    fun `android-test-27 shared-leave-01 取得中のretryが離脱の後に失敗してもErroredにならない`() = runTest {
        // verifies: shared-leave-01 / android-test-27
        val (pA, pB) = listOf("pA", "pB").map { podcast(id = it) }
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        val retryGate = CompletableDeferred<Unit>()
        var pBFetches = 0
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id ->
                if (id == "pB") {
                    pBFetches++
                    if (pBFetches >= 2) retryGate.await()
                    throw exception
                }
                pA
            },
            onUpdatePlaybackPosition = { _, _ -> pA },
        )
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.addToQueue(pB)
        emitPlaying(player)
        emit(player, PlaybackState.Ended(300.0))
        assertEquals(erroredIdOnly("pB", SessionErrorReason.FetchFailed), coordinator.session.value)
        coordinator.dismissNotice()
        launch { coordinator.retry() }
        runCurrent() // 2 回目の pB の取得で止まる

        coordinator.stopForSubjectLeave()
        retryGate.complete(Unit)
        runCurrent()

        assertEquals(2, pBFetches)
        assertTrue(coordinator.session.value is NothingPlaying)
        assertNull(coordinator.notice.value)
    }

    @Test
    fun `android-test-27 shared-leave-01 完聴で始まった前進は離脱の後に次を再生しない`() = runTest {
        // verifies: shared-leave-01 / android-test-27
        val (pA, pB) = listOf("pA", "pB").map { podcast(id = it) }
        val fetchGate = CompletableDeferred<Unit>()
        val apiClient = gatedApiClient(listOf(pA, pB), fetchGates = mapOf("pB" to fetchGate))
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.addToQueue(pB)
        emitPlaying(player)
        emit(player, PlaybackState.Ended(300.0)) // 前進が pB の取得で止まる
        assertTrue("pB" in apiClient.fetchPodcastCalls)

        coordinator.stopForSubjectLeave()
        fetchGate.complete(Unit)
        runCurrent()

        assertTrue(player.prepareCalls.none { it == playable(pB).audioUrl })
        assertTrue(coordinator.session.value is NothingPlaying)
    }

    @Test
    fun `android-test-27 shared-leave-01 playMutexを待っていたCACHEDの開始も離脱の後に再生しない`() = runTest {
        // verifies: shared-leave-01 / android-test-27
        val (pA, pC) = listOf("pA", "pC").map { podcast(id = it) }
        val fetchGate = CompletableDeferred<Unit>()
        val apiClient = gatedApiClient(listOf(pA), fetchGates = mapOf("pA" to fetchGate))
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        cacheManager.cache("u1", "pC", byteArrayOf(1))
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player, cacheManager)
        launch { coordinator.playNow(pA) }
        launch { coordinator.playNow(pC) }
        runCurrent() // pA が取得で playMutex を保持し、pC は待っている

        coordinator.stopForSubjectLeave()
        fetchGate.complete(Unit)
        runCurrent()

        assertTrue(player.prepareCalls.isEmpty())
        assertTrue(coordinator.session.value is NothingPlaying)
        assertTrue(coordinator.queue.value.items.isEmpty())
    }

    @Test
    fun `android-test-27 shared-leave-01 前の再生の最終同期を待つ間に離脱したら次を再生しない`() = runTest {
        // verifies: shared-leave-01 / android-test-27
        val (pA, pC) = listOf("pA", "pC").map { podcast(id = it) }
        val updateGate = CompletableDeferred<Unit>()
        val apiClient = gatedApiClient(listOf(pA), updateGate = updateGate)
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        cacheManager.cache("u1", "pC", byteArrayOf(1))
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player, cacheManager)
        coordinator.playNow(pA)
        emitPlaying(player)
        launch { coordinator.playNow(pC) }
        runCurrent() // 前の再生（Active の pA）の最終同期の送信で止まる

        coordinator.stopForSubjectLeave()
        updateGate.complete(Unit)
        runCurrent()

        assertTrue(player.prepareCalls.none { it == "file:///cache/audio/u1/pC.mp3" })
        assertTrue(coordinator.session.value is NothingPlaying)
        assertTrue(coordinator.queue.value.items.isEmpty())
    }

    @Test
    fun `android-test-27 shared-leave-01 完聴の直後で前進が始まる前に離脱しても次を再生しない`() = runTest {
        // verifies: shared-leave-01 / android-test-27
        val (pA, pB) = listOf("pA", "pB").map { podcast(id = it) }
        val apiClient = gatedApiClient(listOf(pA, pB))
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.addToQueue(pB)
        emitPlaying(player)

        player.setState(PlaybackState.Ended(300.0)) // 購読が動く前に離脱を呼ぶ
        coordinator.stopForSubjectLeave()
        runCurrent()

        assertTrue(player.prepareCalls.none { it == playable(pB).audioUrl })
        assertTrue(coordinator.session.value is NothingPlaying)
        assertTrue(coordinator.queue.value.items.isEmpty())
    }

    // --- 利用者の開始の優先（android-rule-playback-11・shared-dec-20・shared-session-17） ---
    // 競合の順序は「待ちに入る前に何が積まれたか」で決まる。setState の直後に runCurrent を挟まないのは、完聴の購読が
    // 利用者の開始の本体より先に動き、自動の開始がその時点の世代を読む順を作るため。取得待ちの窓の競合では、gate を開ける前に
    // 利用者の開始の本体を runCurrent で動かす（withContext の再 dispatch により、挟まないと世代が進む前に自動の開始が確定する）。

    private fun PlaybackSession.episodeIdOrNull(): String? = when (this) {
        is NothingPlaying -> null
        is Starting -> episode.id
        is Active -> episode.id
        is Completed -> episode.id
        is Stopped -> episode.id
        is Errored -> ref.id
    }

    /** session の遷移の記録。購読の遅れで値が畳まれないよう、Unconfined の dispatcher で集める。 */
    private fun TestScope.recordSessions(coordinator: PlaybackCoordinator): List<PlaybackSession> {
        val recorded = mutableListOf<PlaybackSession>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { coordinator.session.collect { recorded += it } }
        return recorded
    }

    @Test
    fun `android-rule-playback-11 shared-dec-20 利用者の開始の順番待ちの間に自動の開始が来ても確定するのは利用者のbだけでfetchPodcastとsessionの遷移にcが現れない`() = runTest {
        // verifies: android-rule-playback-11 / shared-dec-20
        val (pA, pB, pC) = listOf("pA", "pB", "pC").map { podcast(id = it) }
        val bGate = CompletableDeferred<Unit>()
        val apiClient = gatedApiClient(listOf(pA, pB, pC), fetchGates = mapOf("pB" to bGate))
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.addToQueue(pC)
        emitPlaying(player)
        val sessions = recordSessions(coordinator)

        player.setState(PlaybackState.Ended(300.0)) // runCurrent を挟まない: 自動の開始は世代を読んで playMutex を待つ
        launch(start = CoroutineStart.UNDISPATCHED) { coordinator.playNow(pB) }
        runCurrent() // pB の本体が playMutex を持って取得で止まり、自動の開始は playMutex を待つ
        bGate.complete(Unit)
        runCurrent()

        assertEquals(listOf("pA", "pB"), apiClient.fetchPodcastCalls)
        assertEquals(listOf(playable(pA).audioUrl, playable(pB).audioUrl), player.prepareCalls)
        assertEquals(starting(pB), coordinator.session.value)
        assertTrue("自動の側の遷移が現れた: $sessions", sessions.none { it.episodeIdOrNull() == "pC" })

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `shared-session-17 利用者の開始の取得中にaが完聴しても自動の次のcへは始まらずbが始まる`() = runTest {
        // verifies: shared-session-17 (1) / android-dec-38
        val (pA, pB, pC) = listOf("pA", "pB", "pC").map { podcast(id = it) }
        val bGate = CompletableDeferred<Unit>()
        val apiClient = gatedApiClient(listOf(pA, pB, pC), fetchGates = mapOf("pB" to bGate))
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.addToQueue(pC)
        emitPlaying(player)
        launch { coordinator.playNow(pB) }
        runCurrent() // pB の取得で止まり、playMutex を保持している

        emit(player, PlaybackState.Ended(300.0))

        assertEquals(Active(playable(pA), 1.0), coordinator.session.value) // 完聴は記録だけで、前進も位置の書込もしない
        assertTrue(apiClient.updatePlaybackPositionCalls.isEmpty())

        bGate.complete(Unit)
        runCurrent()

        assertEquals(starting(pB), coordinator.session.value)
        assertEquals(listOf("pA"), apiClient.markCompletedCalls)
        assertEquals(listOf("pA" to 0.0), apiClient.updatePlaybackPositionCalls) // pB への切替の最終同期の 1 回
        assertTrue(apiClient.fetchPodcastCalls.none { it == "pC" })
        assertEquals(listOf(pC), coordinator.queue.value.upNext)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `shared-session-17 shared-dec-20 aの完聴に続く自動のcの開始が順番を待つ間に利用者がbを開始するとbが始まりcはキューに残る`() = runTest {
        // verifies: shared-session-17 (2) / shared-dec-20
        val (pA, pB, pC) = listOf("pA", "pB", "pC").map { podcast(id = it) }
        val bGate = CompletableDeferred<Unit>()
        val apiClient = gatedApiClient(listOf(pA, pB, pC), fetchGates = mapOf("pB" to bGate))
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.addToQueue(pC)
        emitPlaying(player)
        val sessions = recordSessions(coordinator)

        player.setState(PlaybackState.Ended(300.0))
        launch(start = CoroutineStart.UNDISPATCHED) { coordinator.playNow(pB) }
        runCurrent()
        bGate.complete(Unit)
        runCurrent()

        assertEquals(listOf("pA", "pB"), apiClient.fetchPodcastCalls)
        assertEquals(listOf(playable(pA).audioUrl, playable(pB).audioUrl), player.prepareCalls)
        assertEquals(starting(pB), coordinator.session.value)
        assertTrue("自動の側の遷移が現れた: $sessions", sessions.none { it.episodeIdOrNull() == "pC" })
        assertEquals(pB, coordinator.queue.value.current)
        assertEquals(listOf(pC), coordinator.queue.value.upNext)
        assertSharedInv01(coordinator)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-rule-playback-11 shared-dec-20 自動の開始が取得中に利用者が別のepisodeを開始すると取り直した値を捨ててbだけが始まる`() = runTest {
        // verifies: android-rule-playback-11 / shared-dec-20（取得待ちの窓。取り直した値の判定の前の比較）
        val (pA, pB, pC) = listOf("pA", "pB", "pC").map { podcast(id = it) }
        val cGate = CompletableDeferred<Unit>()
        val apiClient = gatedApiClient(listOf(pA, pB, pC), fetchGates = mapOf("pC" to cGate))
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.addToQueue(pC)
        emitPlaying(player)

        emit(player, PlaybackState.Ended(300.0)) // 自動の開始が cの取得で止まる（playMutex を保持）
        launch { coordinator.playNow(pB) }
        runCurrent() // 利用者の開始の本体が世代を進めて playMutex を待つ
        cGate.complete(Unit)
        runCurrent()

        assertEquals(listOf(playable(pA).audioUrl, playable(pB).audioUrl), player.prepareCalls)
        assertEquals(starting(pB), coordinator.session.value)
        assertEquals(listOf("pA", "pC", "pB"), apiClient.fetchPodcastCalls)
        assertEquals(listOf(pC), coordinator.queue.value.upNext)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-rule-playback-11 shared-dec-20 捨てられる自動の開始の取得が失敗しても失敗は表に出さずbだけが始まる`() = runTest {
        // verifies: android-rule-playback-11 / shared-dec-20（捨てられた自動の開始は失敗を表に出さない）
        val (pA, pB, pC) = listOf("pA", "pB", "pC").map { podcast(id = it) }
        val cGate = CompletableDeferred<Unit>()
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id ->
                when (id) {
                    "pC" -> {
                        cGate.await()
                        throw ApiException.NetworkError(RuntimeException("offline"))
                    }
                    "pB" -> pB
                    else -> pA
                }
            },
            onUpdatePlaybackPosition = { id, _ -> if (id == "pA") pA else pB },
        )
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.addToQueue(pC)
        emitPlaying(player)

        emit(player, PlaybackState.Ended(300.0))
        launch { coordinator.playNow(pB) }
        runCurrent()
        cGate.complete(Unit)
        runCurrent()

        assertNull(coordinator.notice.value)
        assertEquals(starting(pB), coordinator.session.value)
        assertEquals(pB, coordinator.queue.value.current)
        assertEquals(listOf(pC), coordinator.queue.value.upNext)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-rule-playback-11 shared-dec-20 捨てられる自動の開始の取り直した値が生成中でも通知を出さずbだけが始まる`() = runTest {
        // verifies: android-rule-playback-11 / shared-dec-20
        val (pA, pB, pC) = listOf("pA", "pB", "pC").map { podcast(id = it) }
        val pCGenerating = podcast(id = "pC", status = "processing")
        val cGate = CompletableDeferred<Unit>()
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id ->
                when (id) {
                    "pC" -> {
                        cGate.await()
                        pCGenerating
                    }
                    "pB" -> pB
                    else -> pA
                }
            },
            onUpdatePlaybackPosition = { id, _ -> if (id == "pA") pA else pB },
        )
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.addToQueue(pC)
        emitPlaying(player)

        emit(player, PlaybackState.Ended(300.0))
        launch { coordinator.playNow(pB) }
        runCurrent()
        cGate.complete(Unit)
        runCurrent()

        assertNull("生成中の通知が出た", coordinator.notice.value)
        assertEquals(starting(pB), coordinator.session.value)
        assertEquals(pB, coordinator.queue.value.current)
        assertEquals(listOf(pC), coordinator.queue.value.upNext)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-rule-playback-11 shared-dec-20 利用者の開始が開始前に失敗しても待っていた自動の開始は捨てられ失敗が表に出る`() = runTest {
        // verifies: android-rule-playback-11 / shared-dec-20（要求の時点で捨てる。次の利用者の開始は通常どおり動く）
        val (pA, pC) = listOf("pA", "pC").map { podcast(id = it) }
        val pBGenerating = podcast(id = "pB", status = "processing")
        val apiClient = gatedApiClient(listOf(pA, pC))
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.addToQueue(pC)
        emitPlaying(player)

        player.setState(PlaybackState.Ended(300.0))
        launch(start = CoroutineStart.UNDISPATCHED) { coordinator.playNow(pBGenerating) }
        runCurrent()

        assertEquals("生成中のため再生できません", coordinator.notice.value?.message)
        assertEquals(Completed(playable(pA)), coordinator.session.value)
        assertEquals(pA, coordinator.queue.value.current)
        assertEquals(listOf("pA"), apiClient.fetchPodcastCalls)
        assertEquals(0, player.stopCallCount)

        coordinator.playNow(pC)

        assertEquals(starting(pC), coordinator.session.value)
        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-rule-playback-11 shared-dec-20 retryはErrored以外では何もせず待っている自動の開始を捨てない`() = runTest {
        // verifies: android-rule-playback-11 / shared-dec-20（retry は確認を通った後で世代を進める）
        val (pA, pC) = listOf("pA", "pC").map { podcast(id = it) }
        val apiClient = gatedApiClient(listOf(pA, pC))
        val player = FakePlayerController()
        val coordinator = newCoordinator(apiClient, player)
        coordinator.playNow(pA)
        coordinator.addToQueue(pC)
        emitPlaying(player)

        player.setState(PlaybackState.Ended(300.0))
        launch(start = CoroutineStart.UNDISPATCHED) { coordinator.retry() }
        runCurrent()

        assertEquals(starting(pC), coordinator.session.value)
        assertEquals(listOf(playable(pA).audioUrl, playable(pC).audioUrl), player.prepareCalls)

        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-query-playback-03 isPlayingはstateがPlayingのときだけ真で読んだ時点の値を返しcollectは変わった値だけを出す`() = runTest {
        // verifies: android-query-playback-03 / android-dec-59 (e)（stateIn で別の coroutine へ写さない）
        val player = FakePlayerController()
        val coordinator = newCoordinator(FakePodcastApiClient(), player)
        val recorded = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { coordinator.isPlaying.collect { recorded += it } }
        runCurrent()
        val states = listOf(
            PlaybackState.Playing(0.0, 300.0) to true,
            PlaybackState.Paused(position = 1.0, duration = 300.0) to false,
            PlaybackState.Playing(2.0, 300.0) to true,
            PlaybackState.Loading to false,
            PlaybackState.Ended(300.0) to false,
            PlaybackState.Idle to false,
            PlaybackState.Failed(PlaybackFailureReason.Network) to false,
        )

        states.forEach { (state, expected) ->
            player.setState(state)
            // runCurrent を挟まない: 読んだ時点の state から導く
            assertEquals("state=$state", expected, coordinator.isPlaying.value)
            runCurrent()
        }

        assertEquals(listOf(false, true, false, true, false), recorded)
    }

    @Test
    fun `android-dec-59 dismissNoticeは再生の失敗を消し次の開始の確定でもnullのまま`() = runTest {
        val ok = podcast(id = "ok")
        val player = FakePlayerController()
        val coordinator = newCoordinator(queueApiClient(ok), player)
        coordinator.playNow(podcast(id = "bad", status = "processing"))
        assertEquals("生成中のため再生できません", coordinator.notice.value?.message)

        coordinator.dismissNotice()

        assertNull(coordinator.notice.value)
        coordinator.playNow(ok)
        assertNull(coordinator.notice.value)
        coordinator.stopForSubjectLeave()
    }

    @Test
    fun `android-dec-59 再生の失敗は次の開始の確定で消える`() = runTest {
        val ok = podcast(id = "ok")
        val player = FakePlayerController()
        val coordinator = newCoordinator(queueApiClient(ok), player)
        coordinator.playNow(podcast(id = "bad", status = "processing"))
        assertEquals("生成中のため再生できません", coordinator.notice.value?.message)

        coordinator.playNow(ok)

        assertNull(coordinator.notice.value)
        coordinator.stopForSubjectLeave()
    }
}

private class FakeListeningStreakStore : ListeningStreakStore {
    override val listeningStreak: StateFlow<ListeningStreakResponse?> = MutableStateFlow(null)
    override val loadFailed: StateFlow<Boolean> = MutableStateFlow(false)
    var refreshCallCount = 0
        private set

    override suspend fun refresh() {
        refreshCallCount++
    }
}
