package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.engagement.ListeningStreakStore
import com.rioikeda.newslisten.model.ListeningStreakResponse
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.model.QuizAnswerResponse
import com.rioikeda.newslisten.model.QuizGradeResult
import com.rioikeda.newslisten.model.VocabularyItemResponse
import com.rioikeda.newslisten.model.VocabularyListResponse
import com.rioikeda.newslisten.network.ApiException
import com.rioikeda.newslisten.network.AudioCacheManager
import com.rioikeda.newslisten.network.EpisodeDecoder
import com.rioikeda.newslisten.network.FakeFileSystem
import com.rioikeda.newslisten.network.StubNetworkMonitor
import com.rioikeda.newslisten.podcast.PlaybackSession.Active
import com.rioikeda.newslisten.podcast.PlaybackSession.Completed
import com.rioikeda.newslisten.podcast.PlaybackSession.Errored
import com.rioikeda.newslisten.podcast.PlaybackSession.NothingPlaying
import com.rioikeda.newslisten.podcast.PlaybackSession.Starting
import com.rioikeda.newslisten.podcast.PlaybackSession.Stopped
import com.rioikeda.newslisten.preferences.InMemoryPreferencesStore
import com.rioikeda.newslisten.preferences.PreferencesStore
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
 * [PodcastViewModel] の挙動検証（フェーズ5 前半: データ層。Media3 実装はフェーズ7）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Podcast/PodcastViewModel.swift のミラー
 * （再生 URL の再取得のみ ADR-009/spec §6.1-2 準拠で意図的に改善）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PodcastViewModelTest {

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

    private fun TestScope.newViewModel(
        apiClient: FakePodcastApiClient,
        playerController: FakePlayerController,
        cacheManager: AudioCacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache"),
        networkMonitor: StubNetworkMonitor = StubNetworkMonitor(initialIsOnline = true),
        listeningStreakStore: ListeningStreakStore? = null,
        preferencesStore: PreferencesStore = InMemoryPreferencesStore(),
        currentSubject: () -> String? = { null },
    ): PodcastViewModel =
        PodcastViewModel(
            podcastApi = apiClient,
            apiClient = apiClient.learning,
            playerController = playerController,
            cacheManager = cacheManager,
            networkMonitor = networkMonitor,
            dispatcher = StandardTestDispatcher(testScheduler),
            listeningStreakStore = listeningStreakStore,
            preferencesStore = preferencesStore,
            currentSubject = currentSubject,
        )

    /** shared-session-04（shared-inv-01）: 再生中のものとキューの現在位置が食い違わない。公開操作の後に毎回確かめる。 */
    private fun assertSharedInv01(viewModel: PodcastViewModel) {
        val currentId = viewModel.queue.value.current?.id
        assertTrue(
            "shared-session-04: session=${viewModel.session.value} queue.current=$currentId",
            viewModel.session.value.satisfiesInvariantP1(currentId),
        )
    }

    // --- fetchPodcasts ---

    @Test
    fun fetchPodcasts成功でpodcastsが更新される() = runTest {
        val podcasts = listOf(podcast("1"), podcast("2"))
        val apiClient = FakePodcastApiClient(onFetchPodcasts = { podcasts })
        val viewModel = newViewModel(apiClient, FakePlayerController())

        viewModel.fetchPodcasts()

        assertEquals(podcasts, viewModel.podcasts.value)
        assertNull(viewModel.errorMessage.value)
        assertFalse(viewModel.isLoading.value)
    }

    @Test
    fun fetchPodcasts失敗でerrorMessageに反映される() = runTest {
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        val apiClient = FakePodcastApiClient(onFetchPodcasts = { throw exception })
        val viewModel = newViewModel(apiClient, FakePlayerController())

        viewModel.fetchPodcasts()

        assertEquals(exception.message, viewModel.errorMessage.value)
        assertTrue(viewModel.podcasts.value.isEmpty())
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
        val viewModel = newViewModel(apiClient, player)

        viewModel.play(listPodcast)

        assertEquals(listOf("p1"), apiClient.fetchPodcastCalls)
        assertEquals(listOf("https://example.com/p1-fresh.mp3"), player.prepareCalls)
        assertEquals(listOf(freshPodcast.toPlaybackMetadata()), player.metadataCalls)
        assertEquals(1, player.playCallCount)
        assertEquals(freshPodcast, viewModel.currentPodcast.value)
        assertNull(viewModel.errorMessage.value)

        viewModel.stopPlayback()
    }

    @Test
    fun play失敗でerrorMessageに反映されprepareは呼ばれない() = runTest {
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        val apiClient = FakePodcastApiClient(onFetchPodcast = { throw exception })
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)

        viewModel.play(podcast())

        assertEquals(exception.message, viewModel.errorMessage.value)
        assertTrue(player.prepareCalls.isEmpty())
    }

    // --- play: processing/failed の再生ゲート（iOS は暗黙失敗するが Android は明示ガード） ---

    @Test
    fun processingのpodcastはfetchPodcastを呼ばず再生不可メッセージを設定する() = runTest {
        val apiClient = FakePodcastApiClient()
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)

        viewModel.play(podcast(status = "processing"))

        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
        assertTrue(player.prepareCalls.isEmpty())
        assertEquals("生成中のため再生できません", viewModel.errorMessage.value)
    }

    @Test
    fun failedのpodcastはerror_messageを再生不可メッセージに反映する() = runTest {
        val apiClient = FakePodcastApiClient()
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)

        viewModel.play(podcast(status = "failed", errorMessage = "TTS生成に失敗しました"))

        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
        assertEquals("TTS生成に失敗しました", viewModel.errorMessage.value)
    }

    @Test
    fun partial_failedのpodcastはfailedと同じくゲートされる() = runTest {
        val apiClient = FakePodcastApiClient()
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)

        viewModel.play(podcast(status = "partial_failed", errorMessage = null))

        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
        assertEquals("生成に失敗しました", viewModel.errorMessage.value)
    }

    // --- オフライン再生（フェーズ8-C: resolvePlaybackSource 統合。正本: shared-playback-spec.md §6.1） ---

    @Test
    fun キャッシュ済みかつオフラインならfetchPodcastを呼ばずキャッシュURIでprepareして再生する() = runTest {
        val p1 = podcast(id = "p1")
        // fetchPodcast/fetchPodcasts は未スタブのままにし、呼ばれたら即座にテスト失敗させる
        // （CACHED 経路は fetch 系に一切触れない独立経路であることの検証）。
        // updatePlaybackPosition は stopPlayback() の最終同期で呼ばれるためスタブしておく。
        val apiClient = FakePodcastApiClient(onUpdatePlaybackPosition = { _, _ -> p1 })
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        cacheManager.cache("p1", byteArrayOf(1, 2, 3))
        val networkMonitor = StubNetworkMonitor(initialIsOnline = false)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player, cacheManager, networkMonitor)

        viewModel.play(p1)

        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
        assertEquals(listOf("file:///cache/audio/p1.mp3"), player.prepareCalls)
        assertEquals(listOf(p1.toPlaybackMetadata()), player.metadataCalls)
        assertEquals(1, player.playCallCount)
        assertEquals(p1, viewModel.currentPodcast.value)
        assertNull(viewModel.errorMessage.value)

        viewModel.stopPlayback()
    }

    @Test
    fun キャッシュ済みならオンラインでもfetchPodcastを呼ばずキャッシュを優先する() = runTest {
        // spec §6.1: キャッシュ有は署名 URL 失効と無関係に常に最優先（オンライン時も再取得しない）。
        val p1 = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(onUpdatePlaybackPosition = { _, _ -> p1 })
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        cacheManager.cache("p1", byteArrayOf(1))
        val networkMonitor = StubNetworkMonitor(initialIsOnline = true)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player, cacheManager, networkMonitor)

        viewModel.play(p1)

        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
        assertEquals(listOf("file:///cache/audio/p1.mp3"), player.prepareCalls)
        assertEquals(p1, viewModel.currentPodcast.value)

        viewModel.stopPlayback()
    }

    @Test
    fun 未キャッシュかつオフラインなら再生できずエラーメッセージを設定する() = runTest {
        val apiClient = FakePodcastApiClient()
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val networkMonitor = StubNetworkMonitor(initialIsOnline = false)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player, cacheManager, networkMonitor)

        viewModel.play(podcast(id = "p1"))

        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
        assertTrue(player.prepareCalls.isEmpty())
        assertEquals(0, player.playCallCount)
        assertEquals("オフラインのため再生できません", viewModel.errorMessage.value)
    }

    @Test
    fun キャッシュ済み再生中はオフラインでもNetworkErrorを握って位置同期を継続する() = runTest {
        // spec §6.2: オフライン中の位置同期は行われない設計だが、実装は「送信して失敗を握る」
        // 既存方針（iOS syncPlaybackPositionIfNeeded 忠実写像）を維持する。ここでは
        // ApiException.NetworkError を投げても viewModel がクラッシュ・エラー表示せず
        // 継続することを保証する。
        val p1 = podcast(id = "p1")
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        val apiClient = FakePodcastApiClient(onUpdatePlaybackPosition = { _, _ -> throw exception })
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        cacheManager.cache("p1", byteArrayOf(1))
        val networkMonitor = StubNetworkMonitor(initialIsOnline = false)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player, cacheManager, networkMonitor)

        viewModel.play(p1)
        emitPlaying(player)
        player.setPosition(12.5)
        advanceTimeBy(15_000)
        runCurrent()

        assertNull(viewModel.errorMessage.value)

        viewModel.stopPlayback()
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
        val viewModel = newViewModel(apiClient, player)

        viewModel.play(podcast(id = "p1"))
        emitPlaying(player)
        player.setPosition(12.5)
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(listOf("p1" to 12.5, "p1" to 12.5), apiClient.updatePlaybackPositionCalls)

        viewModel.stopPlayback()
    }

    @Test
    fun 一時停止中は周期送信せずpauseへの遷移時に1回だけ送る() = runTest {
        // verifies: android-contract-21 / android-test-22（shared-session-06）
        val fresh = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { fresh },
            onUpdatePlaybackPosition = { _, _ -> fresh },
        )
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)

        viewModel.play(podcast(id = "p1"))
        emitPlaying(player)
        viewModel.togglePlayPause() // 一時停止
        emit(player, PlaybackState.Paused(position = 7.0, duration = 300.0))
        player.setPosition(7.0)
        // pause への遷移で Paused.position を 1 回だけ送る。
        assertEquals(listOf("p1" to 7.0), apiClient.updatePlaybackPositionCalls)

        advanceTimeBy(15_000)
        runCurrent()

        assertFalse(player.isPlaying.value)
        assertEquals(listOf("p1" to 7.0), apiClient.updatePlaybackPositionCalls)

        viewModel.stopPlayback()
    }

    // --- stopPlayback: 最終同期 + タイマー停止（iOS stopPlayback():355 転記） ---

    @Test
    fun stopPlaybackは最終同期をしてタイマーを止め以後PATCHが飛ばない() = runTest {
        val fresh = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { fresh },
            onUpdatePlaybackPosition = { _, _ -> fresh },
        )
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)

        viewModel.play(podcast(id = "p1"))
        emitPlaying(player)
        player.setPosition(42.0)

        viewModel.stopPlayback()

        assertEquals(listOf("p1" to 42.0), apiClient.updatePlaybackPositionCalls)
        assertEquals(1, player.stopCallCount)
        assertNull(viewModel.currentPodcast.value)

        // タイマー停止後は時間を進めても追加の PATCH が飛ばない。
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf("p1" to 42.0), apiClient.updatePlaybackPositionCalls)
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
        val viewModel = newViewModel(apiClient, player)
        viewModel.play(podcast(id = "p1"))

        player.setPosition(50.0)
        viewModel.skipBackward()
        assertEquals(listOf(35.0), player.seekCalls)

        player.setPosition(10.0)
        viewModel.skipBackward()
        assertEquals(listOf(35.0, 0.0), player.seekCalls)

        viewModel.stopPlayback()
    }

    @Test
    fun skipForwardは現在位置から30秒進んでseekToに伝わりdurationで上限クランプする() = runTest {
        val fresh = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { fresh },
            onUpdatePlaybackPosition = { _, _ -> fresh },
        )
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.play(podcast(id = "p1"))

        player.setPosition(50.0)
        viewModel.skipForward()
        assertEquals(listOf(80.0), player.seekCalls)

        player.setDuration(100.0)
        player.setPosition(90.0)
        viewModel.skipForward()
        assertEquals(listOf(80.0, 100.0), player.seekCalls)

        viewModel.stopPlayback()
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
        val viewModel = newViewModel(apiClient, player)
        viewModel.play(podcast(id = "p1"))

        PlaybackConstants.speeds.forEach { viewModel.setSpeed(it) }

        // 先頭の 1 件は、開始の確定時に player へ設定した既定速度（InMemoryPreferencesStore の既定 1.0）。
        assertEquals(listOf(1.0f) + PlaybackConstants.speeds.map { it.toFloat() }, player.speedCalls)

        viewModel.stopPlayback()
    }

    // --- togglePlayPause ---

    @Test
    fun togglePlayPauseは再生中なら一時停止し停止中なら再生する() = runTest {
        val fresh = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { fresh },
            onUpdatePlaybackPosition = { _, _ -> fresh },
        )
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.play(podcast(id = "p1"))
        assertTrue(player.isPlaying.value)

        viewModel.togglePlayPause()
        assertFalse(player.isPlaying.value)
        assertEquals(1, player.pauseCallCount)

        viewModel.togglePlayPause()
        assertTrue(player.isPlaying.value)
        assertEquals(2, player.playCallCount)

        viewModel.stopPlayback()
    }

    // --- 再生完了で停止 ---

    @Test
    fun `再生完了イベントではキュー遷移前のPodcast IDを完聴記録する`() = runTest {
        val fresh = podcast(id = "p1")
        lateinit var viewModel: PodcastViewModel
        var currentIdWhenMarked: String? = null
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { fresh },
            onUpdatePlaybackPosition = { _, _ -> fresh },
            onMarkCompleted = { currentIdWhenMarked = viewModel.currentPodcast.value?.id },
        )
        val player = FakePlayerController()
        viewModel = newViewModel(apiClient, player)
        viewModel.play(podcast(id = "p1"))
        player.setPosition(99.0)

        emit(player, PlaybackState.Ended(300.0))

        assertEquals(listOf("p1"), apiClient.markCompletedCalls)
        assertEquals("p1", currentIdWhenMarked)
        assertEquals(1, player.stopCallCount)
        assertEquals(fresh, viewModel.currentPodcast.value)
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
        val viewModel = newViewModel(
            apiClient = apiClient,
            playerController = player,
            listeningStreakStore = streakStore,
        )
        viewModel.playNow(p1)
        viewModel.playNext(p2)

        emit(player, PlaybackState.Ended(300.0))

        assertEquals(p2, viewModel.currentPodcast.value)
        assertEquals(1, streakStore.refreshCallCount)
        assertEquals(2, player.playCallCount)
        viewModel.stopPlayback()
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
        val viewModel = newViewModel(apiClient, player)

        viewModel.play(podcast(id = "pA"))
        viewModel.stopPlayback()
        // ここで PlayerController が release() 済みだと Fake が AssertionError を投げる
        // （実機 ExoPlayer の「release 後は再利用不可」契約をテストで顕在化させる）。
        viewModel.play(podcast(id = "pB"))

        assertEquals(2, player.prepareCalls.size)
        assertEquals(2, player.playCallCount)
        assertEquals(podcastB, viewModel.currentPodcast.value)

        viewModel.stopPlayback()
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
        val viewModel = newViewModel(apiClient, player)

        viewModel.play(podcast(id = "pA"))
        emitPlaying(player)
        player.setPosition(20.0)

        // stopPlayback() を挟まず直接 B へ切り替える（play() 冒頭の内部停止処理を経由）。
        viewModel.play(podcast(id = "pB"))
        emit(player, PlaybackState.Loading)
        emitPlaying(player)

        assertEquals(podcastB, viewModel.currentPodcast.value)
        // 切り替え時に A の最終位置が一度だけ同期される。
        assertEquals(listOf("pA" to 20.0), apiClient.updatePlaybackPositionCalls)

        apiClient.updatePlaybackPositionCalls.clear()
        player.setPosition(5.0)
        advanceTimeBy(15_000)
        runCurrent()

        // 以後は B の同期のみが継続し、A の同期は再開しない。
        assertEquals(listOf("pB" to 5.0), apiClient.updatePlaybackPositionCalls)

        viewModel.stopPlayback()
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
        val viewModel = newViewModel(apiClient, player)

        launch { viewModel.play(podcast(id = "pA")) }
        launch { viewModel.play(podcast(id = "pB")) }
        // WHY advanceUntilIdle() を使わない: startPositionSync の while(isActive) ループは
        // 永久に次の delay(15000) を積み続けるため、advanceUntilIdle() は終了しない
        // （既存テストが advanceTimeBy + runCurrent の有界な組み合わせを使っているのと同じ理由）。
        // ここでは両方の fetchPodcast(delay 1_000) が解決するのに十分な 2_000ms だけ進める。
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(podcastB, viewModel.currentPodcast.value)
        emitPlaying(player) // Starting の B が Active になって周期の timer を張る

        apiClient.updatePlaybackPositionCalls.clear()
        player.setPosition(3.0)
        advanceTimeBy(15_000)
        runCurrent()

        // A 側の同期タイマーが孤児化して生き残っていれば "pA" への PATCH も混ざって観測される。
        assertEquals(listOf("pB" to 3.0), apiClient.updatePlaybackPositionCalls)

        viewModel.stopPlayback()
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
        val viewModel = newViewModel(apiClient, player)

        viewModel.playNow(p1)

        assertEquals(p1, viewModel.currentPodcast.value)
        assertEquals(listOf(p1), viewModel.queue.value.items)
        assertEquals(p1, viewModel.queue.value.current)

        viewModel.stopPlayback()
    }

    @Test
    fun playNowはキュー内に既にあれば重複挿入せずジャンプして再生する() = runTest {
        val p1 = podcast(id = "p1")
        val p2 = podcast(id = "p2")
        val apiClient = queueApiClient(p1, p2)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)

        viewModel.playNow(p1)
        viewModel.playNext(p2) // キュー = [p1*, p2]（再生中は変わらない）

        viewModel.playNow(p2)

        assertEquals(p2, viewModel.currentPodcast.value)
        assertEquals(listOf(p1, p2), viewModel.queue.value.items)
        assertEquals(p2, viewModel.queue.value.current)

        viewModel.stopPlayback()
    }

    @Test
    fun playNowは再生中に未登録の別Podcastを指定するとキューに追加され切り替わって再生される() = runTest {
        val p1 = podcast(id = "p1")
        val p2 = podcast(id = "p2")
        val apiClient = queueApiClient(p1, p2)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)

        viewModel.playNow(p1)
        viewModel.playNow(p2)

        assertEquals(listOf(p1, p2), viewModel.queue.value.items)
        assertEquals(p2, viewModel.queue.value.current)
        assertEquals(p2, viewModel.currentPodcast.value)
        assertEquals(2, player.playCallCount)

        viewModel.stopPlayback()
    }

    @Test
    fun playNextはキューに反映し未再生なら即再生する() = runTest {
        val p1 = podcast(id = "p1")
        val apiClient = queueApiClient(p1)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)

        viewModel.playNext(p1)

        assertEquals(p1, viewModel.currentPodcast.value)
        assertEquals(listOf(p1), viewModel.queue.value.items)

        viewModel.stopPlayback()
    }

    @Test
    fun playNextは再生中なら現在の次に挿入するだけで再生を継続する() = runTest {
        val p1 = podcast(id = "p1")
        val p2 = podcast(id = "p2")
        val apiClient = queueApiClient(p1, p2)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)

        viewModel.playNow(p1)
        viewModel.playNext(p2)

        assertEquals(p1, viewModel.currentPodcast.value)
        assertEquals(listOf(p1, p2), viewModel.queue.value.items)
        assertEquals(1, player.playCallCount)

        viewModel.stopPlayback()
    }

    @Test
    fun addToQueueはキューに反映し未再生なら即再生する() = runTest {
        val p1 = podcast(id = "p1")
        val apiClient = queueApiClient(p1)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)

        viewModel.addToQueue(p1)

        assertEquals(p1, viewModel.currentPodcast.value)
        assertEquals(listOf(p1), viewModel.queue.value.items)

        viewModel.stopPlayback()
    }

    @Test
    fun addToQueueは再生中なら末尾に追加するだけで再生を継続する() = runTest {
        val p1 = podcast(id = "p1")
        val p2 = podcast(id = "p2")
        val apiClient = queueApiClient(p1, p2)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)

        viewModel.playNow(p1)
        viewModel.addToQueue(p2)

        assertEquals(p1, viewModel.currentPodcast.value)
        assertEquals(listOf(p1, p2), viewModel.queue.value.items)
        assertEquals(1, player.playCallCount)

        viewModel.stopPlayback()
    }

    @Test
    fun 再生完了で次があれば自動的に次を再生する() = runTest {
        val p1 = podcast(id = "p1")
        val p2 = podcast(id = "p2")
        val apiClient = queueApiClient(p1, p2)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)

        viewModel.playNow(p1)
        viewModel.playNext(p2)

        emit(player, PlaybackState.Ended(300.0))

        assertEquals(listOf("p1", "p2"), apiClient.fetchPodcastCalls)
        assertEquals(p2, viewModel.currentPodcast.value)
        assertEquals(p2, viewModel.queue.value.current)
        assertEquals(2, player.playCallCount)

        // 自動遷移後の位置同期は新しいエピソード（p2）へ向く。
        apiClient.updatePlaybackPositionCalls.clear()
        emitPlaying(player)
        player.setPosition(3.0)
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(listOf("p2" to 3.0), apiClient.updatePlaybackPositionCalls)

        viewModel.stopPlayback()
    }

    @Test
    fun `再生完了でキュー末尾ならプレイヤーだけ停止してPodcastを保持する`() = runTest {
        val p1 = podcast(id = "p1")
        val apiClient = queueApiClient(p1)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)

        viewModel.playNow(p1)

        emit(player, PlaybackState.Ended(300.0))

        assertEquals(p1, viewModel.currentPodcast.value)
        assertEquals(1, player.stopCallCount)
    }

    // --- 自動遷移×再生ゲートの整合性（review指摘: advance後のnextがgateされる場合に
    //     queueだけ進んでcurrentPodcastが旧曲のまま詰まる不整合を防ぐ） ---

    @Test
    fun 自動遷移先がprocessingの場合エラー設定して停止しqueueとcurrentPodcastが整合する() = runTest {
        val p1 = podcast(id = "p1")
        val p2 = podcast(id = "p2", status = "processing")
        val apiClient = queueApiClient(p1, p2)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)

        viewModel.playNow(p1)
        viewModel.playNext(p2)

        emit(player, PlaybackState.Ended(300.0))

        assertEquals("生成中のため再生できません", viewModel.errorMessage.value)
        assertNull(viewModel.currentPodcast.value)
        assertEquals(p2, viewModel.queue.value.current)
        assertEquals(1, player.stopCallCount)
        assertTrue(apiClient.fetchPodcastCalls.none { it == "p2" })

        viewModel.stopPlayback()
    }

    @Test
    fun removeFromQueueは待機列の要素をキューモデルから取り除き再生中には影響しない() = runTest {
        val p1 = podcast(id = "p1")
        val p2 = podcast(id = "p2")
        val apiClient = queueApiClient(p1, p2)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)

        viewModel.playNow(p1)
        viewModel.playNext(p2)

        viewModel.removeFromQueue(p2.id)

        assertEquals(listOf(p1), viewModel.queue.value.items)
        assertEquals(p1, viewModel.currentPodcast.value)

        viewModel.stopPlayback()
    }

    @Test
    fun removeFromQueueで現在再生中のidを渡してもキューモデルの更新のみで実際の再生は継続する() = runTest {
        // iOS 準拠（PodcastViewModel.swift:313-315）: removeFromQueue は queue.remove(id:) を
        // 呼ぶだけの純粋なキュー操作で、実際の再生（AVPlayer/PlayerController）には触れない。
        // キューモデル上は次の要素が currentIndex に昇格するが、currentPodcast/実再生は不変。
        val p1 = podcast(id = "p1")
        val p2 = podcast(id = "p2")
        val apiClient = queueApiClient(p1, p2)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)

        viewModel.playNow(p1)
        viewModel.playNext(p2)

        viewModel.removeFromQueue(p1.id)

        assertEquals(listOf(p2), viewModel.queue.value.items)
        assertEquals(p2, viewModel.queue.value.current)
        assertEquals(p1, viewModel.currentPodcast.value)
        assertEquals(0, player.stopCallCount)

        viewModel.stopPlayback()
    }

    @Test
    fun moveUpNextはqueueのStateFlowに反映される() = runTest {
        val p1 = podcast(id = "p1")
        val p2 = podcast(id = "p2")
        val p3 = podcast(id = "p3")
        val apiClient = queueApiClient(p1, p2, p3)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)

        viewModel.playNow(p1)
        viewModel.playNext(p2)
        viewModel.playNext(p3) // 現在の直後へ挿入: キュー = [p1*, p3, p2]

        viewModel.moveUpNext(from = 1, toOffset = 0)

        assertEquals(listOf(p2, p3), viewModel.queue.value.upNext)

        viewModel.stopPlayback()
    }

    // --- ダウンロード・音声キャッシュ（フェーズ8-B。正本: ADR-027 / iOS PodcastViewModel.swift:141-174） ---

    @Test
    fun downloadは開始時にdownloadingIdsへ追加し完了後に除去する() = runTest {
        val p1 = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { delay(1_000); p1 },
            onDownloadAudio = { byteArrayOf(1, 2, 3) },
        )
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val viewModel = newViewModel(apiClient, FakePlayerController(), cacheManager)

        launch { viewModel.download(p1) }
        runCurrent()
        assertEquals(setOf("p1"), viewModel.downloadingIds.value)

        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(emptySet<String>(), viewModel.downloadingIds.value)
    }

    @Test
    fun download成功でキャッシュに保存されdownloadedIdsに追加される() = runTest {
        val p1 = podcast(id = "p1", audioUrl = "https://example.com/p1-stale.mp3")
        val fresh = podcast(id = "p1", audioUrl = "https://example.com/p1-fresh.mp3")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { fresh },
            onDownloadAudio = { byteArrayOf(9, 9) },
        )
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val viewModel = newViewModel(apiClient, FakePlayerController(), cacheManager)

        viewModel.download(p1)

        assertEquals(listOf("p1"), apiClient.fetchPodcastCalls)
        assertEquals(listOf("https://example.com/p1-fresh.mp3"), apiClient.downloadAudioCalls)
        assertTrue(cacheManager.isCached("p1"))
        assertEquals(setOf("p1"), viewModel.downloadedIds.value)
        assertEquals(emptySet<String>(), viewModel.downloadingIds.value)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun download失敗でerrorMessageに反映されキャッシュされずdownloadingIdsから除去される() = runTest {
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        val apiClient = FakePodcastApiClient(onFetchPodcast = { throw exception })
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val viewModel = newViewModel(apiClient, FakePlayerController(), cacheManager)

        viewModel.download(podcast(id = "p1"))

        assertEquals(exception.message, viewModel.errorMessage.value)
        assertFalse(cacheManager.isCached("p1"))
        assertEquals(emptySet<String>(), viewModel.downloadingIds.value)
        assertEquals(emptySet<String>(), viewModel.downloadedIds.value)
    }

    @Test
    fun downloadは既にdownloadingまたはdownloaded中なら何もしない() = runTest {
        val p1 = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { p1 },
            onDownloadAudio = { byteArrayOf(1) },
        )
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val viewModel = newViewModel(apiClient, FakePlayerController(), cacheManager)

        viewModel.download(p1)
        assertEquals(1, apiClient.fetchPodcastCalls.size)

        // 既に downloadedIds に含まれるため、再度呼んでも fetchPodcast は増えない
        // （iOS PodcastViewModel.swift:142 の二重起動防止と同じガード）。
        viewModel.download(p1)

        assertEquals(1, apiClient.fetchPodcastCalls.size)
    }

    @Test
    fun removeDownloadはキャッシュを削除しdownloadedIdsから除去する() = runTest {
        val p1 = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { p1 },
            onDownloadAudio = { byteArrayOf(1) },
        )
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val viewModel = newViewModel(apiClient, FakePlayerController(), cacheManager)
        viewModel.download(p1)

        viewModel.removeDownload("p1")

        assertFalse(cacheManager.isCached("p1"))
        assertEquals(emptySet<String>(), viewModel.downloadedIds.value)
    }

    @Test
    fun removeDownloadは不正なidの場合errorMessageに反映する() = runTest {
        val apiClient = FakePodcastApiClient()
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val viewModel = newViewModel(apiClient, FakePlayerController(), cacheManager)

        viewModel.removeDownload("../evil")

        assertNotNull(viewModel.errorMessage.value)
    }

    @Test
    fun downloadはキャッシュ書き込みのIOExceptionでもクラッシュせずdownloadingIdsから除去される() = runTest {
        // WARNING指摘: AudioCacheManager.cache() が投げるIOExceptionをラップせず素通しにすると
        // downloadingIds は finally で除去されるものの、例外がコルーチンを未捕捉のまま抜けクラッシュしうる。
        // AudioCacheException.WriteFailed へラップし既存の catch (e: AudioCacheException) で
        // 捕捉されることを確認する。
        val p1 = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { p1 },
            onDownloadAudio = { byteArrayOf(1) },
        )
        val fileSystem = FakeFileSystem().apply { writeException = IOException("disk full") }
        val cacheManager = AudioCacheManager(fileSystem, baseDir = "/cache")
        val viewModel = newViewModel(apiClient, FakePlayerController(), cacheManager)

        viewModel.download(p1)

        assertFalse(cacheManager.isCached("p1"))
        assertEquals(emptySet<String>(), viewModel.downloadingIds.value)
        assertEquals(emptySet<String>(), viewModel.downloadedIds.value)
        assertNotNull(viewModel.errorMessage.value)
    }

    @Test
    fun fetchPodcasts後にキャッシュ済みidがdownloadedIdsへ同期される() = runTest {
        val p1 = podcast(id = "p1")
        val p2 = podcast(id = "p2")
        val fileSystem = FakeFileSystem()
        val cacheManager = AudioCacheManager(fileSystem, baseDir = "/cache")
        cacheManager.cache("p1", byteArrayOf(1)) // 事前に p1 だけキャッシュ済みの状態を再現する
        val apiClient = FakePodcastApiClient(onFetchPodcasts = { listOf(p1, p2) })
        val viewModel = newViewModel(apiClient, FakePlayerController(), cacheManager)

        viewModel.fetchPodcasts()

        assertEquals(setOf("p1"), viewModel.downloadedIds.value)
    }

    // --- cancelDownloadsAndClearCache（logout×ダウンロード競合。共有端末対応 spec §6.3） ---
    //
    // WHY このテストが必要か: AuthViewModel.onSubjectLeave（Dispatchers.Default 上で動く）と
    // PodcastViewModel.download()（limitedParallelism(1)）は、download() が fetchPodcast という
    // suspend 境界を挟むために無同期になり得る。download が suspend 中に logout のキャッシュ全削除が
    // 素通りしてしまうと、削除後に download が再開してファイルを書き込み、downloadedIds にも
    // 残留してしまう（共有端末に前ユーザーの音声データが残る＝ spec §6.3 の存在理由に反する）。

    @Test
    fun cancelDownloadsAndClearCacheはfetchPodcast中の進行中downloadを中断しキャッシュ書き込みを残さない() = runTest {
        val p1 = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { delay(1_000); p1 },
            onDownloadAudio = { byteArrayOf(1, 2, 3) },
        )
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val viewModel = newViewModel(apiClient, FakePlayerController(), cacheManager)

        launch { viewModel.download(p1) }
        runCurrent()
        assertEquals(setOf("p1"), viewModel.downloadingIds.value)

        // logout 相当の割り込み。download が fetchPodcast の delay(1_000) で中断している最中に呼ぶ。
        viewModel.cancelDownloadsAndClearCache()

        assertFalse(cacheManager.isCached("p1"))
        assertEquals(emptySet<String>(), viewModel.downloadedIds.value)
        assertEquals(emptySet<String>(), viewModel.downloadingIds.value)

        // 中断された download の続き（fetchPodcast 完了後の cache() 呼び出し）が
        // 遅れて実行されてファイルが復活することがないか、時間を進めて再確認する。
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(cacheManager.isCached("p1"))
        assertEquals(emptySet<String>(), viewModel.downloadedIds.value)
    }

    @Test
    fun cancelDownloadsAndClearCacheは完了済みのダウンロードキャッシュも削除しdownloadedIdsを空にする() = runTest {
        val p1 = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { p1 },
            onDownloadAudio = { byteArrayOf(1) },
        )
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val viewModel = newViewModel(apiClient, FakePlayerController(), cacheManager)
        viewModel.download(p1)
        assertTrue(cacheManager.isCached("p1"))

        viewModel.cancelDownloadsAndClearCache()

        assertFalse(cacheManager.isCached("p1"))
        assertEquals(emptySet<String>(), viewModel.downloadedIds.value)
    }

    // --- submitQuizAnswers（要件1: podcastId 明示化） ---

    @Test
    fun submitQuizAnswersは指定のpodcastIdで送信する() = runTest {
        val quizResponse = QuizAnswerResponse(
            correctCount = 2,
            total = 3,
            correctRate = 2.0 / 3.0,
            results = listOf(
                QuizGradeResult(
                    questionIndex = 0,
                    isCorrect = true,
                    correctIndex = 0,
                    selectedIndex = 0,
                ),
                QuizGradeResult(
                    questionIndex = 1,
                    isCorrect = true,
                    correctIndex = 1,
                    selectedIndex = 1,
                ),
                QuizGradeResult(
                    questionIndex = 2,
                    isCorrect = false,
                    correctIndex = 2,
                    selectedIndex = 0,
                ),
            ),
        )
        var submittedId = ""
        val apiClient = FakePodcastApiClient(
            onSubmitQuizAnswers = { podcastId, _ ->
                submittedId = podcastId
                quizResponse
            },
        )
        val viewModel = newViewModel(apiClient, FakePlayerController())

        val response = viewModel.submitQuizAnswers("podcast-1", listOf(0, 1, 0))

        assertEquals("podcast-1", submittedId)
        assertEquals(2, response.correctCount)
        assertEquals(3, response.total)
    }

    @Test
    fun submitQuizAnswersの送信前にcurrentPodcastが変わっても指定podcastIdで送信する() = runTest {
        val p1 = podcast(id = "p1")
        val p2 = podcast(id = "p2")
        val quizResponse = QuizAnswerResponse(
            correctCount = 1,
            total = 1,
            correctRate = 1.0,
            results = listOf(
                QuizGradeResult(
                    questionIndex = 0,
                    isCorrect = true,
                    correctIndex = 0,
                    selectedIndex = 0,
                ),
            ),
        )
        var submittedId = ""
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id -> if (id == "p1") p1 else p2 },
            onSubmitQuizAnswers = { podcastId, _ ->
                submittedId = podcastId
                quizResponse
            },
            onUpdatePlaybackPosition = { id, _ -> if (id == "p1") p1 else p2 },
        )
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)

        viewModel.play(p1)
        viewModel.play(p2)

        val response = viewModel.submitQuizAnswers("p1", listOf(0))

        assertEquals("p1", submittedId)
        assertEquals(p2, viewModel.currentPodcast.value)

        viewModel.stopPlayback()
    }

    @Test
    fun submitQuizAnswersが失敗時は例外をスロー() = runTest {
        val apiClient = FakePodcastApiClient(
            onSubmitQuizAnswers = { _, _ -> throw ApiException.HttpError(500) },
        )
        val viewModel = newViewModel(apiClient, FakePlayerController())

        try {
            viewModel.submitQuizAnswers("podcast-1", listOf(0))
            throw AssertionError("例外をスロー予定")
        } catch (e: ApiException.HttpError) {
            assertEquals(500, e.code)
        }
    }

    @Test
    fun `登録語彙の初期取得成功で習得済み判定へ反映する`() = runTest {
        val apiClient = FakePodcastApiClient(
            onFetchVocabulary = {
                VocabularyListResponse(
                    vocabulary = listOf(vocabularyItem("p1", "Resilient")),
                    count = 1,
                )
            },
        )
        val viewModel = newViewModel(apiClient, FakePlayerController())

        viewModel.loadVocabularyRegistrations()

        assertTrue(viewModel.isVocabularyRegistered("p1", " resilient "))
    }

    @Test
    fun `登録語彙の初期取得失敗はPodcast表示を妨げない`() = runTest {
        val apiClient = FakePodcastApiClient(
            onFetchVocabulary = { throw ApiException.HttpError(500) },
        )
        val viewModel = newViewModel(apiClient, FakePlayerController())

        viewModel.loadVocabularyRegistrations()

        assertTrue(viewModel.registeredVocabularyKeys.value.isEmpty())
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `語彙の習得登録は冪等で同じ語を二重送信しない`() = runTest {
        val apiClient = FakePodcastApiClient(
            onSaveVocabulary = { podcastId, term -> vocabularyItem(podcastId, term) },
        )
        val viewModel = newViewModel(apiClient, FakePlayerController())

        viewModel.saveVocabulary("p1", "Resilient")
        viewModel.saveVocabulary("p1", " resilient ")

        assertEquals(listOf("p1" to "Resilient"), apiClient.saveVocabularyCalls)
        assertTrue(viewModel.isVocabularyRegistered("p1", "resilient"))
    }

    // --- 再生可否は Episode の種類で判定する（shared-session-08・android-deriv-26・android-dec-22） ---

    @Test
    fun `shared-session-08 completedでもerrorMessageを持つ行はfetchせず再生不可でerrorMessageを出す`() = runTest {
        val apiClient = FakePodcastApiClient()
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)

        viewModel.play(podcast(status = "completed", errorMessage = "X"))

        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
        assertTrue(player.prepareCalls.isEmpty())
        assertEquals("X", viewModel.errorMessage.value)
    }

    @Test
    fun `android-deriv-26 completedでもURLが空の行はfetchせず再生不可になる`() = runTest {
        val apiClient = FakePodcastApiClient()
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)

        viewModel.play(podcast(status = "completed", audioUrl = ""))

        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
        assertTrue(player.prepareCalls.isEmpty())
        assertEquals("生成に失敗しました", viewModel.errorMessage.value)
    }

    @Test
    fun `android-deriv-26 未知のstatusの行はfetchせず再生不可になる`() = runTest {
        val apiClient = FakePodcastApiClient()
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)

        viewModel.play(podcast(status = "weird"))

        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
        assertTrue(player.prepareCalls.isEmpty())
        assertEquals("生成に失敗しました", viewModel.errorMessage.value)
    }

    @Test
    fun `android-dec-22 failedでerrorMessageが空文字なら生成に失敗しましたを出す`() = runTest {
        val apiClient = FakePodcastApiClient()
        val viewModel = newViewModel(apiClient, FakePlayerController())

        viewModel.play(podcast(status = "failed", errorMessage = ""))

        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
        assertEquals("生成に失敗しました", viewModel.errorMessage.value)
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
        val viewModel = newViewModel(apiClient, player)

        viewModel.play(listed)

        assertEquals(listOf("p1"), apiClient.fetchPodcastCalls)
        assertEquals(listOf("https://example.com/p1-fresh.mp3"), player.prepareCalls)
        assertEquals(1, player.playCallCount)
        assertNull(viewModel.errorMessage.value)

        viewModel.stopPlayback()
    }

    // --- 取り直した値が再生不可でも、前の再生を壊さない（android-deriv-17・android-dec-21） ---

    @Test
    fun `android-deriv-17 一覧の判定で拒否されても再生中の位置同期は続く`() = runTest {
        val pA = podcast(id = "pA")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { pA },
            onUpdatePlaybackPosition = { _, _ -> pA },
        )
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.play(pA)
        emitPlaying(player)

        viewModel.play(podcast(id = "pX", status = "failed", errorMessage = "boom"))
        apiClient.updatePlaybackPositionCalls.clear()
        player.setPosition(5.0)
        advanceTimeBy(15_000)
        runCurrent()

        assertEquals(listOf("pA" to 5.0), apiClient.updatePlaybackPositionCalls)
        assertEquals(pA, viewModel.currentPodcast.value)
        assertEquals("boom", viewModel.errorMessage.value)

        viewModel.stopPlayback()
    }

    @Test
    fun `android-deriv-17 取り直した値がGeneratingなら前の再生を止めず生成中を出す`() = runTest {
        val pA = podcast(id = "pA")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id -> if (id == "pB") podcast(id = "pB", status = "processing") else pA },
            onUpdatePlaybackPosition = { _, _ -> pA },
        )
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.play(pA)

        viewModel.play(podcast(id = "pB"))

        assertEquals(0, player.stopCallCount)
        assertEquals(pA, viewModel.currentPodcast.value)
        assertEquals("生成中のため再生できません", viewModel.errorMessage.value)

        viewModel.stopPlayback()
    }

    @Test
    fun `android-deriv-17 取り直した値がFailedなら前の再生を止めずそのerrorMessageを出す`() = runTest {
        val pA = podcast(id = "pA")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id -> if (id == "pB") podcast(id = "pB", status = "failed", errorMessage = "E") else pA },
            onUpdatePlaybackPosition = { _, _ -> pA },
        )
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.play(pA)

        viewModel.play(podcast(id = "pB"))

        assertEquals(0, player.stopCallCount)
        assertEquals(pA, viewModel.currentPodcast.value)
        assertEquals("E", viewModel.errorMessage.value)

        viewModel.stopPlayback()
    }

    @Test
    fun `android-deriv-17 取り直した値が拒否された後も前の再生の位置同期は続く`() = runTest {
        val pA = podcast(id = "pA")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id -> if (id == "pB") podcast(id = "pB", status = "processing") else pA },
            onUpdatePlaybackPosition = { _, _ -> pA },
        )
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.play(pA)
        emitPlaying(player)
        viewModel.play(podcast(id = "pB"))

        apiClient.updatePlaybackPositionCalls.clear()
        player.setPosition(8.0)
        advanceTimeBy(15_000)
        runCurrent()

        assertEquals(listOf("pA" to 8.0), apiClient.updatePlaybackPositionCalls)

        viewModel.stopPlayback()
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
        val viewModel = newViewModel(apiClient, player)
        viewModel.play(pA)
        emitPlaying(player)

        launch { viewModel.play(podcast(id = "pB")) }
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
        assertEquals(pBFresh, viewModel.currentPodcast.value)

        viewModel.stopPlayback()
    }

    @Test
    fun `shared-session-13 android-deriv-17 fetchがApiExceptionなら前の再生を止めずerrorMessageを出す`() = runTest {
        val pA = podcast(id = "pA")
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id -> if (id == "pB") throw exception else pA },
            onUpdatePlaybackPosition = { _, _ -> pA },
        )
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.play(pA)

        viewModel.play(podcast(id = "pB"))

        assertEquals(0, player.stopCallCount)
        assertEquals(pA, viewModel.currentPodcast.value)
        assertEquals(exception.message, viewModel.errorMessage.value)

        viewModel.stopPlayback()
    }

    @Test
    fun `android-deriv-17 自動前進先の取り直し値がGeneratingなら止めてcurrentPodcastをnullにする`() = runTest {
        val pA = podcast(id = "pA")
        val pB = podcast(id = "pB")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id -> if (id == "pB") podcast(id = "pB", status = "processing") else pA },
            onUpdatePlaybackPosition = { _, _ -> pA },
        )
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.playNow(pA)
        viewModel.playNext(pB)

        emit(player, PlaybackState.Ended(300.0))

        assertNull(viewModel.currentPodcast.value)
        assertEquals("生成中のため再生できません", viewModel.errorMessage.value)
        assertEquals(1, player.stopCallCount)
    }

    @Test
    fun `android-deriv-17 自動前進先の取り直し値がFailedならそのerrorMessageを出して止める`() = runTest {
        val pA = podcast(id = "pA")
        val pB = podcast(id = "pB")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id -> if (id == "pB") podcast(id = "pB", status = "failed", errorMessage = "E") else pA },
            onUpdatePlaybackPosition = { _, _ -> pA },
        )
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.playNow(pA)
        viewModel.playNext(pB)

        emit(player, PlaybackState.Ended(300.0))

        assertNull(viewModel.currentPodcast.value)
        assertEquals("E", viewModel.errorMessage.value)
        assertEquals(1, player.stopCallCount)
    }

    @Test
    fun `android-deriv-17 downloadで取り直した値がFailedならcacheせずerrorMessageを出す`() = runTest {
        val p1 = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { podcast(id = "p1", status = "failed", errorMessage = "E") },
            onDownloadAudio = { byteArrayOf(1) },
        )
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val viewModel = newViewModel(apiClient, FakePlayerController(), cacheManager)

        viewModel.download(p1)

        assertTrue(apiClient.downloadAudioCalls.isEmpty())
        assertFalse(cacheManager.isCached("p1"))
        assertEquals(emptySet<String>(), viewModel.downloadedIds.value)
        assertEquals(emptySet<String>(), viewModel.downloadingIds.value)
        assertEquals("E", viewModel.errorMessage.value)
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
        val viewModel = newViewModel(apiClient, player, cacheManager)
        viewModel.play(pA)
        emitPlaying(player)
        cacheManager.cache("pB", byteArrayOf(1))

        viewModel.play(pB)

        assertEquals(1, player.stopCallCount)
        assertEquals(1, apiClient.updatePlaybackPositionCalls.count { it.first == "pA" })
        assertEquals(listOf("pA"), apiClient.fetchPodcastCalls)
        assertEquals("file:///cache/audio/pB.mp3", player.prepareCalls.last())
        assertEquals(pB, viewModel.currentPodcast.value)

        viewModel.stopPlayback()
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
        val viewModel = newViewModel(apiClient, player, networkMonitor = networkMonitor)
        viewModel.play(pA)
        networkMonitor.setOnline(false)

        viewModel.play(pB)

        assertEquals(0, player.stopCallCount)
        assertEquals(0, apiClient.updatePlaybackPositionCalls.count { it.first == "pA" })
        assertEquals(listOf("pA"), apiClient.fetchPodcastCalls)
        assertEquals(pA, viewModel.currentPodcast.value)
        assertEquals("オフラインのため再生できません", viewModel.errorMessage.value)

        viewModel.stopPlayback()
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
        val viewModel = newViewModel(apiClient, player)
        viewModel.playNow(pA)
        viewModel.addToQueue(pB)
        viewModel.addToQueue(pC)

        // pB の play が fetch の往復で playMutex を保持している間に、pA の完了通知が届く。
        launch { viewModel.playNow(pB) }
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
        assertEquals(pB, viewModel.queue.value.current)
        assertEquals(pBFresh, viewModel.currentPodcast.value)
        assertEquals("https://example.com/pB-fresh.mp3", player.prepareCalls.last())
        assertTrue(apiClient.fetchPodcastCalls.none { it == "pC" })

        viewModel.stopPlayback()
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
        val preferences = InMemoryPreferencesStore(initialDefaultPlaybackSpeed = 1.5)
        val listedA = podcast(id = "pA")
        val freshA = podcast(id = "pA", audioUrl = "https://example.com/pA-fresh.mp3", playbackPositionSeconds = 120.0)
        val listedB = podcast(id = "pB", playbackPositionSeconds = 40.0)
        val apiClient = FakePodcastApiClient(onFetchPodcast = { freshA }, onUpdatePlaybackPosition = { _, _ -> freshA })
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        cacheManager.cache("pB", byteArrayOf(1))
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player, cacheManager, preferencesStore = preferences)

        viewModel.playNow(listedA) // NETWORK: 再開位置は取り直した値（一覧の 0 ではない）
        assertEquals(listOf(1.5f), player.speedCalls)
        assertEquals(listOf(120.0), player.seekCalls)

        viewModel.setSpeed(2.0)
        viewModel.playNow(listedB) // CACHED: 再開位置は一覧の値

        assertEquals(listOf(1.5f, 2.0f, 1.5f), player.speedCalls)
        assertEquals(listOf(120.0, 40.0), player.seekCalls)
        assertEquals(1.5, preferences.defaultPlaybackSpeed.value, 0.0)
        viewModel.stopPlayback()
    }

    @Test
    fun `android-test-05 shared-session-10 android-contract-06 再開位置が0か末尾2秒窓ならseekToを呼ばず窓の手前なら適用する`() = runTest {
        val episodes = listOf(0.0, 299.0, 298.0, 297.5).mapIndexed { i, seconds ->
            podcast(id = "p$i", playbackPositionSeconds = seconds)
        }
        val player = FakePlayerController()
        val viewModel = newViewModel(queueApiClient(*episodes.toTypedArray()), player)

        episodes.take(3).forEach { viewModel.playNow(it) }
        assertTrue(player.seekCalls.isEmpty())

        viewModel.playNow(episodes[3])
        assertEquals(listOf(297.5), player.seekCalls)
        viewModel.stopPlayback()
    }

    // 完聴後の自動前進の開始前の失敗（shared-session-01・02・03・12）

    @Test
    fun `android-test-06 shared-session-01 shared-session-02 shared-session-04 shared-session-12 android-contract-07 advance後のNETWORK取得失敗はIdOnlyのErroredで進まずretryが取得からやり直す`() = runTest {
        val (pA, pB, pC, pD) = listOf("pA", "pB", "pC", "pD").map { podcast(id = it) }
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        var bFails = true
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id -> if (id == "pB") (if (bFails) throw exception else pB) else if (id == "pC") pC else pA },
            onUpdatePlaybackPosition = { _, _ -> pA },
        )
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.playNow(pA)
        viewModel.addToQueue(pB)
        viewModel.addToQueue(pC)
        val stopsBefore = player.stopCallCount

        emit(player, PlaybackState.Ended(300.0))

        assertEquals(erroredIdOnly("pB", SessionErrorReason.FetchFailed), viewModel.session.value)
        assertEquals(pB, viewModel.queue.value.current) // 自動で pC へ進まない
        assertEquals(stopsBefore + 1, player.stopCallCount)
        assertEquals(exception.message, viewModel.errorMessage.value)
        assertEquals(listOf("pA"), apiClient.markCompletedCalls)
        assertNull(viewModel.nowPlaying.value)
        assertNull(viewModel.currentPodcast.value)
        assertTrue(apiClient.fetchPodcastCalls.none { it == "pC" })
        assertSharedInv01(viewModel)

        val preparesAfterFailure = player.prepareCalls.size
        viewModel.addToQueue(pD) // Errored の間は即再生しない
        assertEquals(preparesAfterFailure, player.prepareCalls.size)
        assertEquals(listOf(pA, pB, pC, pD), viewModel.queue.value.items)
        assertSharedInv01(viewModel)

        bFails = false
        viewModel.retry()

        assertEquals(listOf("pA", "pB", "pB"), apiClient.fetchPodcastCalls)
        assertEquals(starting(pB), viewModel.session.value)
        assertEquals(playable(pB).audioUrl, player.prepareCalls.last())
        assertNull(viewModel.errorMessage.value)
        assertSharedInv01(viewModel)
        viewModel.stopPlayback()
    }

    @Test
    fun `android-test-07 shared-session-03 shared-session-12 android-contract-08 advance後のオフライン未キャッシュはfetchせずSourceUnavailableのErrored`() = runTest {
        val (pA, pB) = listOf("pA", "pB").map { podcast(id = it) }
        val apiClient = queueApiClient(pA, pB)
        val networkMonitor = StubNetworkMonitor(initialIsOnline = true)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player, networkMonitor = networkMonitor)
        viewModel.playNow(pA)
        viewModel.addToQueue(pB)
        networkMonitor.setOnline(false)

        emit(player, PlaybackState.Ended(300.0))

        assertEquals(listOf("pA"), apiClient.fetchPodcastCalls)
        assertEquals(erroredIdOnly("pB", SessionErrorReason.SourceUnavailable), viewModel.session.value)
        assertEquals("オフラインのため再生できません", viewModel.errorMessage.value)
        assertEquals(pB, viewModel.queue.value.current)
        assertSharedInv01(viewModel)
        viewModel.stopPlayback()
    }

    @Test
    fun `android-test-06 shared-session-12 advance先の取り直し値がGeneratingならNotPlayableのErrored`() = runTest {
        val pA = podcast(id = "pA")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id -> if (id == "pB") podcast(id = "pB", status = "processing") else pA },
            onUpdatePlaybackPosition = { _, _ -> pA },
        )
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.playNow(pA)
        viewModel.addToQueue(podcast(id = "pB"))

        emit(player, PlaybackState.Ended(300.0))

        assertEquals(erroredIdOnly("pB", SessionErrorReason.NotPlayable), viewModel.session.value)
        assertEquals("生成中のため再生できません", viewModel.errorMessage.value)
        assertSharedInv01(viewModel)
    }

    // 手動の開始が開始前に落ちるとき、キューも session も player も変えない（shared-session-13・shared-dec-16）

    private suspend fun TestScope.assertManualStartRejectedKeepsState(
        listedB: Episode,
        expectedMessage: String?,
        expectFetchOfB: Boolean,
        online: Boolean = true,
        fetchB: suspend () -> Episode = { listedB },
        start: suspend (PodcastViewModel, Episode) -> Unit = { viewModel, episode -> viewModel.playNow(episode) },
    ) {
        val pA = podcast(id = "pA")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id -> if (id == "pB") fetchB() else pA },
            onUpdatePlaybackPosition = { _, _ -> pA },
        )
        val networkMonitor = StubNetworkMonitor(initialIsOnline = true)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player, networkMonitor = networkMonitor)
        viewModel.playNow(pA)
        emitPlaying(player)
        assertTrue(viewModel.session.value is Active)
        networkMonitor.setOnline(online)
        val queueBefore = viewModel.queue.value
        val sessionBefore = viewModel.session.value
        val playerBefore = listOf(player.prepareCalls.toList(), player.seekCalls.toList(), player.speedCalls.toList())
        val countsBefore = listOf(player.playCallCount, player.stopCallCount)
        val updatesBefore = apiClient.updatePlaybackPositionCalls.toList()
        val fetchesBefore = apiClient.fetchPodcastCalls.toList()

        start(viewModel, listedB)

        assertEquals(queueBefore, viewModel.queue.value)
        assertEquals(sessionBefore, viewModel.session.value)
        assertEquals(playerBefore, listOf(player.prepareCalls, player.seekCalls, player.speedCalls))
        assertEquals(countsBefore, listOf(player.playCallCount, player.stopCallCount))
        assertEquals(updatesBefore, apiClient.updatePlaybackPositionCalls)
        assertEquals(expectedMessage, viewModel.errorMessage.value)
        if (!expectFetchOfB) assertEquals(fetchesBefore, apiClient.fetchPodcastCalls)
        assertSharedInv01(viewModel)
        viewModel.stopPlayback()
    }

    @Test
    fun `android-test-08 shared-session-13 shared-dec-16 一覧の判定がGeneratingなら状態を変えず生成中を出す`() = runTest {
        assertManualStartRejectedKeepsState(podcast(id = "pB", status = "processing"), "生成中のため再生できません", expectFetchOfB = false)
    }

    @Test
    fun `android-test-08 shared-session-13 shared-dec-16 一覧の判定がFailedなら状態を変えずそのerrorMessageを出す`() = runTest {
        assertManualStartRejectedKeepsState(podcast(id = "pB", status = "failed", errorMessage = "E"), "E", expectFetchOfB = false)
    }

    @Test
    fun `android-test-08 shared-session-13 shared-dec-16 オフライン未キャッシュなら状態を変えずfetchもせずオフラインを出す`() = runTest {
        assertManualStartRejectedKeepsState(podcast(id = "pB"), "オフラインのため再生できません", expectFetchOfB = false, online = false)
    }

    @Test
    fun `android-test-08 shared-session-13 shared-dec-16 取得がApiExceptionなら状態を変えずe_messageを出す`() = runTest {
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        assertManualStartRejectedKeepsState(podcast(id = "pB"), exception.message, expectFetchOfB = true, fetchB = { throw exception })
    }

    @Test
    fun `android-test-08 shared-session-13 shared-dec-16 取り直した値がGeneratingなら状態を変えず生成中を出す`() = runTest {
        assertManualStartRejectedKeepsState(
            podcast(id = "pB"), "生成中のため再生できません", expectFetchOfB = true,
            fetchB = { podcast(id = "pB", status = "processing") },
        )
    }

    @Test
    fun `android-test-08 shared-session-13 shared-dec-16 playでも取得失敗は状態を変えない`() = runTest {
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        assertManualStartRejectedKeepsState(
            podcast(id = "pB"), exception.message, expectFetchOfB = true,
            fetchB = { throw exception }, start = { viewModel, episode -> viewModel.play(episode) },
        )
    }

    @Test
    fun `android-test-08 shared-session-13 shared-dec-16 NothingPlayingでaddToQueueした再生不可のepisodeはキューに残りsessionはNothingPlayingのまま`() = runTest {
        val pB = podcast(id = "pB", status = "processing")
        val player = FakePlayerController()
        val viewModel = newViewModel(FakePodcastApiClient(), player)

        viewModel.addToQueue(pB)

        assertEquals(listOf(pB), viewModel.queue.value.items)
        assertTrue(viewModel.session.value is NothingPlaying)
        assertEquals("生成中のため再生できません", viewModel.errorMessage.value)
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
        val viewModel = newViewModel(apiClient, player)
        viewModel.playNow(pA)
        viewModel.addToQueue(pB)
        viewModel.addToQueue(pC)

        launch { viewModel.playNow(pB) }
        runCurrent()
        emit(player, PlaybackState.Ended(300.0))

        assertEquals(listOf("pA"), apiClient.markCompletedCalls)
        assertEquals(starting(pA), viewModel.session.value) // playerEnded を呼んでいない
        assertEquals(pA, viewModel.queue.value.current)
        assertSharedInv01(viewModel)

        fetchGate.complete(Unit)
        runCurrent()

        assertEquals(starting(pB), viewModel.session.value)
        assertEquals(pB, viewModel.queue.value.current)
        assertTrue(apiClient.fetchPodcastCalls.none { it == "pC" })
        assertSharedInv01(viewModel)
        viewModel.stopPlayback()
    }

    // player の失敗と CACHED 経路の invalidate（android-contract-11 の Coordinator 部分）

    @Test
    fun `android-test-11 android-contract-11 CACHED再生がFailedのSourceかDecodeならinvalidateしてErroredのLoadedになり次回はNETWORK`() = runTest {
        for (reason in listOf(PlaybackFailureReason.Source, PlaybackFailureReason.Decode)) {
            val pA = podcast(id = "pA")
            val apiClient = FakePodcastApiClient(onFetchPodcast = { pA }, onUpdatePlaybackPosition = { _, _ -> pA })
            val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
            cacheManager.cache("pA", byteArrayOf(1))
            val player = FakePlayerController()
            val viewModel = newViewModel(apiClient, player, cacheManager)
            viewModel.playNow(pA)
            assertEquals("file:///cache/audio/pA.mp3", player.prepareCalls.last())

            emit(player, PlaybackState.Failed(reason))

            assertFalse("$reason", cacheManager.isCached("pA"))
            assertEquals(erroredLoaded(pA, reason), viewModel.session.value)
            assertNull(viewModel.nowPlaying.value)
            assertSharedInv01(viewModel)

            viewModel.retry() // キャッシュを捨てたので取得からやり直す

            assertEquals(listOf("pA"), apiClient.fetchPodcastCalls)
            assertEquals(playable(pA).audioUrl, player.prepareCalls.last())
            assertTrue(viewModel.session.value is Starting)
            viewModel.stopPlayback()
        }
    }

    @Test
    fun `android-test-11 android-contract-11 CACHED再生でもFailedのNetworkとUnknownではinvalidateしない`() = runTest {
        for (reason in listOf(PlaybackFailureReason.Network, PlaybackFailureReason.Unknown(code = 7))) {
            val pA = podcast(id = "pA")
            val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
            cacheManager.cache("pA", byteArrayOf(1))
            val player = FakePlayerController()
            val viewModel = newViewModel(queueApiClient(pA), player, cacheManager)
            viewModel.playNow(pA)

            emit(player, PlaybackState.Failed(reason))

            assertTrue("$reason", cacheManager.isCached("pA"))
            assertEquals(erroredLoaded(pA, reason), viewModel.session.value)
            viewModel.stopPlayback()
        }
    }

    @Test
    fun `android-test-11 android-contract-11 NETWORK再生のFailedのSourceではキャッシュがあってもinvalidateしない`() = runTest {
        val pA = podcast(id = "pA")
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val player = FakePlayerController()
        val viewModel = newViewModel(queueApiClient(pA), player, cacheManager)
        viewModel.playNow(pA)
        cacheManager.cache("pA", byteArrayOf(1)) // NETWORK で始めた後にキャッシュが現れた状況

        emit(player, PlaybackState.Failed(PlaybackFailureReason.Source))

        assertTrue(cacheManager.isCached("pA"))
        assertEquals(erroredLoaded(pA, PlaybackFailureReason.Source), viewModel.session.value)
        viewModel.stopPlayback()
    }

    @Test
    fun `android-test-11 android-contract-11 invalidateがAudioCacheExceptionを投げてもErroredのPlayerへ進みその後のplayer事象も受け取る`() = runTest {
        val badId = podcast(id = "a.b") // "." を含む id は invalidate が InvalidId を投げる。isCached は検証しないので CACHED で始まる
        val fileSystem = FakeFileSystem()
        fileSystem.write("/cache/audio/a.b.mp3", byteArrayOf(1))
        val player = FakePlayerController()
        val viewModel = newViewModel(queueApiClient(badId), player, AudioCacheManager(fileSystem, baseDir = "/cache"))
        viewModel.playNow(badId)

        emit(player, PlaybackState.Failed(PlaybackFailureReason.Source))
        assertEquals(erroredLoaded(badId, PlaybackFailureReason.Source), viewModel.session.value)

        viewModel.retry() // 購読が生きていれば、この後の事象が session に届く
        emitPlaying(player)
        assertEquals(Active(playable(badId), 1.0), viewModel.session.value)
        viewModel.stopPlayback()
    }

    @Test
    fun `android-test-11 android-contract-11 currentSubjectは開始の要求1回につき1回だけ呼ばれplayer事象では呼ばれない`() = runTest {
        val (pA, pB) = listOf("pA", "pB").map { podcast(id = it) }
        var subjectCalls = 0
        val player = FakePlayerController()
        val viewModel = newViewModel(queueApiClient(pA, pB), player, currentSubject = { subjectCalls++; "user-1" })

        viewModel.playNow(pA)
        emit(player, PlaybackState.Failed(PlaybackFailureReason.Network))
        assertEquals(1, subjectCalls)

        viewModel.retry()
        assertEquals(2, subjectCalls)

        viewModel.playNow(pB)
        viewModel.stopPlayback()
        assertEquals(3, subjectCalls)
    }

    // player の事象の受け方（android-deriv-15 の本 PR の範囲）

    @Test
    fun `android-test-01 android-deriv-15 表に無い事象は無視されsessionは変わらず例外も出ない`() = runTest {
        val pA = podcast(id = "pA")
        val apiClient = queueApiClient(pA)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.playNow(pA)

        emit(player, PlaybackState.Loading)
        assertEquals(starting(pA), viewModel.session.value)

        emit(player, PlaybackState.Paused(position = 0.0, duration = 300.0)) // Starting の列の Paused は playerStarted（位置は送らない）
        assertEquals(Active(playable(pA), 1.0), viewModel.session.value)
        assertTrue(apiClient.updatePlaybackPositionCalls.isEmpty())
        emitPlaying(player)
        emit(player, PlaybackState.Paused(position = 3.0, duration = 300.0)) // Active の列の Paused は、直前が Playing なら位置を 1 回送る
        assertEquals(Active(playable(pA), 1.0), viewModel.session.value)
        assertEquals(listOf("pA" to 3.0), apiClient.updatePlaybackPositionCalls)

        viewModel.stopPlayback() // Active からの停止直前の位置は positionSeconds（Fake では 0.0）
        assertEquals(listOf("pA" to 3.0, "pA" to 0.0), apiClient.updatePlaybackPositionCalls)
        emit(player, PlaybackState.Failed(PlaybackFailureReason.Network)) // NothingPlaying への Failed
        assertTrue(viewModel.session.value is NothingPlaying)
    }

    // stopPlayback（shared-session-11・android-dec-04）

    @Test
    fun `android-test-26 shared-session-11 Activeからstopしたらplayerを止め最終同期しNothingPlayingになりキューは保持する`() = runTest {
        val (pA, pB) = listOf("pA", "pB").map { podcast(id = it) }
        val apiClient = queueApiClient(pA, pB)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.playNow(pA)
        viewModel.addToQueue(pB)
        emitPlaying(player)
        player.setPosition(42.0)

        viewModel.stopPlayback()
        viewModel.stopPlayback() // 2 回目は何も起きない

        assertTrue(viewModel.session.value is NothingPlaying)
        assertEquals(1, player.stopCallCount)
        assertEquals(listOf("pA" to 42.0), apiClient.updatePlaybackPositionCalls)
        assertEquals(listOf(pA, pB), viewModel.queue.value.items)
        assertEquals(pA, viewModel.queue.value.current)
        assertNull(viewModel.currentPodcast.value)
        assertNull(viewModel.nowPlaying.value)
        assertSharedInv01(viewModel)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf("pA" to 42.0), apiClient.updatePlaybackPositionCalls)
    }

    @Test
    fun `android-test-26 shared-session-11 NothingPlayingからstopしてもAPIもplayerも呼ばれない`() = runTest {
        val apiClient = FakePodcastApiClient()
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)

        viewModel.stopPlayback()

        assertTrue(viewModel.session.value is NothingPlaying)
        assertEquals(0, player.stopCallCount)
        assertTrue(apiClient.updatePlaybackPositionCalls.isEmpty())
        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
        assertTrue(apiClient.markCompletedCalls.isEmpty())
        assertTrue(player.prepareCalls.isEmpty())
    }

    @Test
    fun `android-test-26 shared-session-11 ErroredのIdOnlyからstopしたら位置は送らずplayerを止めてNothingPlayingになる`() = runTest {
        // 自動前進の開始前の失敗の後。現行は currentPodcast が null なので位置を送らない。
        val (pA, pB) = listOf("pA", "pB").map { podcast(id = it) }
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id -> if (id == "pB") throw exception else pA },
            onUpdatePlaybackPosition = { _, _ -> pA },
        )
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.playNow(pA)
        viewModel.addToQueue(pB)
        emit(player, PlaybackState.Ended(300.0))
        assertEquals(erroredIdOnly("pB", SessionErrorReason.FetchFailed), viewModel.session.value)
        val updatesBefore = apiClient.updatePlaybackPositionCalls.toList()
        val stopsBefore = player.stopCallCount

        viewModel.stopPlayback()
        advanceTimeBy(30_000)
        runCurrent()

        assertTrue(viewModel.session.value is NothingPlaying)
        assertEquals(updatesBefore, apiClient.updatePlaybackPositionCalls)
        assertEquals(stopsBefore + 1, player.stopCallCount)
        assertEquals(pB, viewModel.queue.value.current)
        assertSharedInv01(viewModel)
    }

    @Test
    fun `android-test-26 shared-session-11 ErroredのLoadedからstopしたら位置は送らずタイマーも止まる`() = runTest {
        // player の失敗の後（Errored の Loaded）。Active からの停止だけが位置を送る（android-dec-36）。
        // Failed で timer が止まることも、30 秒進めても送られないことで確かめる。
        val pA = podcast(id = "pA")
        val apiClient = queueApiClient(pA)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.playNow(pA)
        emitPlaying(player)
        emit(player, PlaybackState.Failed(PlaybackFailureReason.Network))
        assertEquals(erroredLoaded(pA, PlaybackFailureReason.Network), viewModel.session.value)
        player.setPosition(33.0)
        val stopsBefore = player.stopCallCount

        viewModel.stopPlayback()
        advanceTimeBy(30_000) // Failed で止めないと timer が孤児になる
        runCurrent()

        assertTrue(viewModel.session.value is NothingPlaying)
        assertTrue(apiClient.updatePlaybackPositionCalls.isEmpty())
        assertEquals(stopsBefore + 1, player.stopCallCount)
        assertSharedInv01(viewModel)
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
        val viewModel = newViewModel(apiClient, player)
        assertTrue(viewModel.session.value is NothingPlaying)

        viewModel.playNow(pA) // NothingPlaying から Starting
        viewModel.addToQueue(pB)
        assertEquals(starting(pA), viewModel.session.value)
        assertSharedInv01(viewModel)

        emitPlaying(player) // Starting から Active
        assertEquals(Active(playable(pA), 1.0), viewModel.session.value)
        assertSharedInv01(viewModel)

        emit(player, PlaybackState.Ended(300.0)) // Active から Completed（pB の取得と完聴の記録を保留している間に観測する）
        assertEquals(Completed(playable(pA)), viewModel.session.value)
        assertEquals(pA, viewModel.queue.value.current)
        assertSharedInv01(viewModel)

        fetchGate.complete(Unit) // Completed から次の Starting（advance。markCompleted の応答は待たない）
        runCurrent()
        assertEquals(starting(pB), viewModel.session.value)
        assertEquals(pB, viewModel.queue.value.current)
        assertSharedInv01(viewModel)

        emit(player, PlaybackState.Loading)
        emitPlaying(player)
        emit(player, PlaybackState.Ended(300.0)) // 末尾: Active から Completed を経て Stopped
        assertEquals(Stopped(playable(pB)), viewModel.session.value)
        assertEquals(playable(pB), viewModel.currentPodcast.value)
        assertSharedInv01(viewModel)

        viewModel.playNow(pA) // Stopped から Starting
        assertEquals(starting(pA), viewModel.session.value)
        assertSharedInv01(viewModel)

        markGate.complete(Unit)
        runCurrent()
        assertEquals(listOf("pA", "pB"), apiClient.markCompletedCalls)
        viewModel.stopPlayback()
    }

    @Test
    fun `android-test-01 shared-session-04 Activeからの手動の開始とStartingからの手動の開始がStartingになる`() = runTest {
        val (pA, pB, pC) = listOf("pA", "pB", "pC").map { podcast(id = it) }
        val player = FakePlayerController()
        val viewModel = newViewModel(queueApiClient(pA, pB, pC), player)
        viewModel.playNow(pA)
        emitPlaying(player)
        assertTrue(viewModel.session.value is Active)

        viewModel.playNow(pB) // Active から playNow の辺
        assertEquals(starting(pB), viewModel.session.value)
        assertSharedInv01(viewModel)

        viewModel.playNow(pC) // Starting の割り込みは stop の辺を経て start
        assertEquals(starting(pC), viewModel.session.value)
        assertEquals(pC, viewModel.queue.value.current)
        assertSharedInv01(viewModel)
        viewModel.stopPlayback()
    }

    @Test
    fun `android-test-01 shared-session-04 失敗の辺とretryとErroredからの手動の開始がsessionに現れる`() = runTest {
        val (pA, pB) = listOf("pA", "pB").map { podcast(id = it) }
        val player = FakePlayerController()
        val viewModel = newViewModel(queueApiClient(pA, pB), player)
        viewModel.playNow(pA)

        emit(player, PlaybackState.Failed(PlaybackFailureReason.Network)) // Starting から Errored
        assertEquals(erroredLoaded(pA, PlaybackFailureReason.Network), viewModel.session.value)
        assertSharedInv01(viewModel)

        viewModel.retry() // Errored から Starting（retry）
        assertEquals(starting(pA), viewModel.session.value)

        emitPlaying(player)
        emit(player, PlaybackState.Failed(PlaybackFailureReason.Decode)) // Active から Errored
        assertEquals(erroredLoaded(pA, PlaybackFailureReason.Decode), viewModel.session.value)
        assertSharedInv01(viewModel)

        viewModel.playNow(pB) // Errored から Starting（start）
        assertEquals(starting(pB), viewModel.session.value)
        assertEquals(pB, viewModel.queue.value.current)
        assertSharedInv01(viewModel)
        viewModel.stopPlayback()
    }

    @Test
    fun `android-test-01 shared-session-04 retryはErrored以外では何もしない`() = runTest {
        val pA = podcast(id = "pA")
        val apiClient = queueApiClient(pA)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)

        viewModel.retry() // NothingPlaying
        assertTrue(viewModel.session.value is NothingPlaying)
        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
        assertTrue(player.prepareCalls.isEmpty())

        viewModel.playNow(pA)
        emitPlaying(player)
        val activeBefore = viewModel.session.value
        viewModel.retry() // Active
        assertEquals(activeBefore, viewModel.session.value)
        assertEquals(listOf("pA"), apiClient.fetchPodcastCalls)
        assertEquals(1, player.prepareCalls.size)
        assertEquals(0, player.stopCallCount)
        viewModel.stopPlayback()
    }

    // 旧経路の完聴は player の事象を注入しなくても進む（android-dec-31）

    @Test
    fun `android-test-01 android-dec-31 Startingのまま完聴しても例外なく次のepisodeのStartingへ進む`() = runTest {
        val (pA, pB) = listOf("pA", "pB").map { podcast(id = it) }
        val apiClient = queueApiClient(pA, pB)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.playNow(pA)
        viewModel.addToQueue(pB)

        emit(player, PlaybackState.Ended(300.0))

        assertEquals(listOf("pA"), apiClient.markCompletedCalls)
        assertEquals(starting(pB), viewModel.session.value)
        assertEquals(pB, viewModel.queue.value.current)
        assertSharedInv01(viewModel)
        viewModel.stopPlayback()
    }

    @Test
    fun `android-test-01 android-dec-31 Startingのまま末尾で完聴しても例外なくStoppedになりepisodeを保持する`() = runTest {
        val pA = podcast(id = "pA")
        val apiClient = queueApiClient(pA)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.playNow(pA)

        emit(player, PlaybackState.Ended(300.0))

        assertEquals(Stopped(playable(pA)), viewModel.session.value)
        assertEquals(pA, viewModel.currentPodcast.value)
        assertEquals(listOf("pA"), apiClient.markCompletedCalls)
        assertSharedInv01(viewModel)
    }

    @Test
    fun `android-test-01 android-dec-31 再生していない状態の完聴の通知は何もしない`() = runTest {
        val pA = podcast(id = "pA")
        val apiClient = queueApiClient(pA)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.playNow(pA)
        viewModel.stopPlayback()
        val callsBefore = listOf(apiClient.markCompletedCalls.toList(), apiClient.fetchPodcastCalls.toList())

        emit(player, PlaybackState.Ended(300.0))

        assertTrue(viewModel.session.value is NothingPlaying)
        assertEquals(callsBefore, listOf(apiClient.markCompletedCalls, apiClient.fetchPodcastCalls))
    }

    // 何も再生していないときだけ即再生する（android-deriv-02）と、キュー操作の後の shared-inv-01

    @Test
    fun `android-deriv-02 Stoppedの間のaddToQueueは即再生しない`() = runTest {
        val (pA, pC) = listOf("pA", "pC").map { podcast(id = it) }
        val player = FakePlayerController()
        val viewModel = newViewModel(queueApiClient(pA, pC), player)
        viewModel.playNow(pA)
        emit(player, PlaybackState.Ended(300.0))
        val preparesBefore = player.prepareCalls.size

        viewModel.addToQueue(pC)

        assertEquals(preparesBefore, player.prepareCalls.size)
        assertEquals(listOf(pA, pC), viewModel.queue.value.items)
        assertEquals(Stopped(playable(pA)), viewModel.session.value)
        assertSharedInv01(viewModel)
    }

    @Test
    fun `shared-session-04 moveUpNextと待機列のremoveFromQueueの後もsessionとキューの現在位置が一致する`() = runTest {
        // removeFromQueue で現在の id を渡す経路は、既存の挙動（キューだけ更新）を保つため対象外。
        val (pA, pB, pC) = listOf("pA", "pB", "pC").map { podcast(id = it) }
        val viewModel = newViewModel(queueApiClient(pA, pB, pC), FakePlayerController())
        viewModel.playNow(pA)
        viewModel.playNext(pB)
        viewModel.playNext(pC)

        viewModel.moveUpNext(from = 1, toOffset = 0)
        assertSharedInv01(viewModel)
        viewModel.removeFromQueue(pC.id)

        assertSharedInv01(viewModel)
        assertEquals(pA, viewModel.queue.value.current)
        viewModel.stopPlayback()
    }

    // nowPlaying・currentPodcast は session の派生（android-temp-05）

    @Test
    fun `android-temp-05 nowPlayingはEpisodePlayableの7fieldをそのまま同じ参照で持つ`() = runTest {
        val pA = playableWithContent("pA")
        val viewModel = newViewModel(queueApiClient(pA), FakePlayerController())

        viewModel.playNow(pA)

        val nowPlaying = viewModel.nowPlaying.value
        assertNotNull(nowPlaying)
        assertEquals(pA.id, nowPlaying!!.episodeId)
        assertEquals(pA.displayTitle, nowPlaying.displayTitle)
        assertEquals(pA.japaneseIntroText, nowPlaying.japaneseIntroText)
        assertSame(pA.transcript, nowPlaying.segments)
        assertSame(pA.glossary, nowPlaying.vocabulary)
        assertSame(pA.quiz, nowPlaying.quiz)
        assertEquals(pA.difficultyCode, nowPlaying.difficulty)
        assertEquals(pA, viewModel.currentPodcast.value)
        viewModel.stopPlayback()
    }

    @Test
    fun `android-temp-05 nowPlayingとcurrentPodcastは状態ごとに同じ非null条件で同期して書き換わる`() = runTest {
        val pA = playableWithContent("pA")
        val player = FakePlayerController()
        val viewModel = newViewModel(queueApiClient(pA), player)
        fun assertDerived(expected: Episode.Playable?) {
            assertEquals(expected, viewModel.currentPodcast.value)
            assertEquals(expected?.id, viewModel.nowPlaying.value?.episodeId)
        }
        assertDerived(null)

        viewModel.playNow(pA)
        assertDerived(pA) // Starting
        emitPlaying(player)
        assertDerived(pA) // Active
        emit(player, PlaybackState.Failed(PlaybackFailureReason.Network))
        assertTrue(viewModel.session.value is Errored)
        assertDerived(null)

        viewModel.retry()
        emitPlaying(player)
        emit(player, PlaybackState.Ended(300.0))
        assertTrue(viewModel.session.value is Stopped)
        assertDerived(pA)

        viewModel.stopPlayback()
        assertDerived(null)
    }

    // --- 連続した開始の切り替え（android-contract-22。baseline: 実装前の現行挙動で green） ---

    @Test
    fun `android-test-24 連続playNowの切り替えで前のidの最終同期は1回出てその後の周期送信は最後のepisodeだけ`() = runTest {
        // verifies: android-contract-22 / android-test-24
        val pA = podcast(id = "pA")
        val pB = podcast(id = "pB")
        val apiClient = queueApiClient(pA, pB)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.playNow(pA)
        emitPlaying(player)
        player.setPosition(20.0)

        viewModel.playNow(pB)
        emit(player, PlaybackState.Loading)
        emitPlaying(player)

        assertEquals(listOf("pA" to 20.0), apiClient.updatePlaybackPositionCalls)

        apiClient.updatePlaybackPositionCalls.clear()
        player.setPosition(5.0)
        advanceTimeBy(15_000)
        runCurrent()

        assertEquals(listOf("pB" to 5.0), apiClient.updatePlaybackPositionCalls)

        viewModel.stopPlayback()
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
        val viewModel = newViewModel(apiClient, player)
        viewModel.play(p1)
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
        val viewModel = newViewModel(apiClient, player)
        viewModel.play(p1)
        emitPlaying(player)
        emit(player, PlaybackState.Ended(300.0))
        val sessionBefore = viewModel.session.value
        val updatesBefore = apiClient.updatePlaybackPositionCalls.toList()

        emit(player, PlaybackState.Ended(301.0))

        assertEquals(listOf("p1"), apiClient.markCompletedCalls)
        assertEquals(sessionBefore, viewModel.session.value)
        assertEquals(updatesBefore, apiClient.updatePlaybackPositionCalls)
    }

    @Test
    fun `android-test-10 shared-session-07 Endedの総時間がnullならEpisodeの総時間を送る`() = runTest {
        // verifies: android-contract-10 / android-test-10
        val p1 = podcast(id = "p1")
        val apiClient = gatedApiClient(listOf(p1))
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.play(p1)
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
        val viewModel = newViewModel(apiClient, player)
        viewModel.play(p1)
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
        val viewModel = newViewModel(apiClient, player)
        viewModel.play(p1)
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
        val viewModel = newViewModel(apiClient, player)
        viewModel.playNow(pA)
        viewModel.addToQueue(pB)
        emitPlaying(player)

        emit(player, PlaybackState.Ended(300.0))

        assertEquals(listOf("pA"), apiClient.markCompletedCalls) // markGate は閉じたまま
        assertEquals(starting(pB), viewModel.session.value)
        assertEquals(pB, viewModel.queue.value.current)
        assertSharedInv01(viewModel)
        markGate.complete(Unit)
        runCurrent()
        viewModel.stopPlayback()
    }

    @Test
    fun `android-test-10 shared-session-07 送信の途中でplayerが位置を0に戻しても送る値はEnded時点の値`() = runTest {
        // verifies: android-contract-10 / android-test-10
        val pA = podcast(id = "pA", durationSeconds = 0)
        val pB = podcast(id = "pB")
        val markGate = CompletableDeferred<Unit>()
        val apiClient = gatedApiClient(listOf(pA, pB), markGate = markGate)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.playNow(pA)
        viewModel.addToQueue(pB)
        emitPlaying(player)
        player.setPosition(120.0)

        emit(player, PlaybackState.Ended(null)) // 次の開始が player.stop() で位置を 0 に戻す
        markGate.complete(Unit)
        runCurrent()

        assertEquals(listOf("pA" to 120.0), apiClient.updatePlaybackPositionCalls)
        viewModel.stopPlayback()
    }

    @Test
    fun `android-test-10 shared-session-04 前進の取得を待つ間に次に再生へ足したepisodeは次のepisodeの後に残る`() = runTest {
        // verifies: android-contract-10 / android-test-10
        val (pA, pB, pX) = listOf("pA", "pB", "pX").map { podcast(id = it) }
        val fetchGate = CompletableDeferred<Unit>()
        val apiClient = gatedApiClient(listOf(pA, pB, pX), fetchGates = mapOf("pB" to fetchGate))
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.playNow(pA)
        viewModel.addToQueue(pB)
        emitPlaying(player)
        emit(player, PlaybackState.Ended(300.0))
        assertEquals(Completed(playable(pA)), viewModel.session.value)
        assertEquals(pA, viewModel.queue.value.current) // 取得を待つ間は完聴した episode のまま
        assertSharedInv01(viewModel)

        viewModel.playNext(pX)
        fetchGate.complete(Unit)
        runCurrent()

        assertEquals(pB, viewModel.queue.value.current)
        assertEquals(listOf(pX), viewModel.queue.value.upNext)
        assertEquals(starting(pB), viewModel.session.value)
        assertSharedInv01(viewModel)
        viewModel.stopPlayback()
    }

    @Test
    fun `android-test-10 shared-session-07 android-dec-38 手動の開始の取得中に届いた完聴が2回でもmarkCompletedは1回でタイマーも止まる`() = runTest {
        // verifies: android-contract-10 / android-test-10
        val (pA, pB) = listOf("pA", "pB").map { podcast(id = it) }
        val fetchGate = CompletableDeferred<Unit>()
        val apiClient = gatedApiClient(listOf(pA, pB), fetchGates = mapOf("pB" to fetchGate))
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.playNow(pA)
        viewModel.addToQueue(pB)
        emitPlaying(player) // Active。周期の timer が動く
        launch { viewModel.playNow(pB) }
        runCurrent() // pB の取得で止まり、playMutex を保持している

        emit(player, PlaybackState.Ended(300.0))
        emit(player, PlaybackState.Ended(301.0))
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(listOf("pA"), apiClient.markCompletedCalls)
        assertTrue(apiClient.updatePlaybackPositionCalls.isEmpty()) // timer は止まり、完聴の位置も送らない
        assertEquals(Active(playable(pA), 1.0), viewModel.session.value)
        assertEquals(pA, viewModel.queue.value.current)

        fetchGate.complete(Unit)
        runCurrent()
        assertEquals(starting(pB), viewModel.session.value)
        viewModel.stopPlayback()
    }

    @Test
    fun `android-test-10 shared-session-07 android-dec-38 手動の開始の取得中に完聴の後でplayerが始まっても2回目の完聴でmarkCompletedを重ねない`() = runTest {
        // verifies: android-contract-10 / android-test-10
        val (pA, pB) = listOf("pA", "pB").map { podcast(id = it) }
        val fetchGate = CompletableDeferred<Unit>()
        val apiClient = gatedApiClient(listOf(pA, pB), fetchGates = mapOf("pB" to fetchGate))
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.playNow(pA) // Starting のまま（Playing を入れない）
        viewModel.addToQueue(pB)
        launch { viewModel.playNow(pB) }
        runCurrent() // pB の取得で止まり、playMutex を保持している

        emit(player, PlaybackState.Ended(300.0))
        emit(player, PlaybackState.Paused(position = 0.0, duration = 300.0)) // playerStarted で Active になる
        emit(player, PlaybackState.Ended(301.0))

        assertEquals(listOf("pA"), apiClient.markCompletedCalls)
        assertEquals(Active(playable(pA), 1.0), viewModel.session.value)
        assertEquals(pA, viewModel.queue.value.current)

        fetchGate.complete(Unit)
        runCurrent()
        assertEquals(starting(pB), viewModel.session.value)
        viewModel.stopPlayback()
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
        val viewModel = newViewModel(apiClient, player)
        viewModel.playNow(pA)
        viewModel.addToQueue(pB)
        emitPlaying(player)
        emit(player, PlaybackState.Ended(300.0))
        assertEquals(erroredIdOnly("pB", SessionErrorReason.FetchFailed), viewModel.session.value)
        val updatesBefore = apiClient.updatePlaybackPositionCalls.toList()

        advanceTimeBy(30_000)
        runCurrent()
        viewModel.stopPlayback()

        assertEquals(updatesBefore, apiClient.updatePlaybackPositionCalls)
    }

    @Test
    fun `android-test-21 shared-session-05 ErroredのLoadedとStoppedとNothingPlayingからstopしても送らない`() = runTest {
        // verifies: android-contract-21 / android-test-21
        run { // Errored の Loaded
            val pA = podcast(id = "pA")
            val apiClient = queueApiClient(pA)
            val player = FakePlayerController()
            val viewModel = newViewModel(apiClient, player)
            viewModel.playNow(pA)
            emitPlaying(player)
            emit(player, PlaybackState.Failed(PlaybackFailureReason.Network))
            assertEquals(erroredLoaded(pA, PlaybackFailureReason.Network), viewModel.session.value)
            val updatesBefore = apiClient.updatePlaybackPositionCalls.toList()
            player.setPosition(50.0)

            viewModel.stopPlayback()

            assertEquals(updatesBefore, apiClient.updatePlaybackPositionCalls)
        }
        run { // Stopped（末尾の完聴の後）
            val pA = podcast(id = "pA")
            val apiClient = queueApiClient(pA)
            val player = FakePlayerController()
            val viewModel = newViewModel(apiClient, player)
            viewModel.playNow(pA)
            emitPlaying(player)
            emit(player, PlaybackState.Ended(300.0))
            assertEquals(Stopped(playable(pA)), viewModel.session.value)
            val updatesBefore = apiClient.updatePlaybackPositionCalls.toList()
            player.setPosition(50.0)

            viewModel.stopPlayback()

            assertEquals(updatesBefore, apiClient.updatePlaybackPositionCalls)
        }
        run { // NothingPlaying
            val apiClient = FakePodcastApiClient()
            val player = FakePlayerController()
            val viewModel = newViewModel(apiClient, player)
            player.setPosition(50.0)

            viewModel.stopPlayback()

            assertTrue(apiClient.updatePlaybackPositionCalls.isEmpty())
        }
    }

    @Test
    fun `android-test-21 shared-session-05 Failedを受けたらタイマーが止まる`() = runTest {
        // verifies: android-contract-21 / android-test-21
        val pA = podcast(id = "pA")
        val apiClient = queueApiClient(pA)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.playNow(pA)
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
        val viewModel = newViewModel(apiClient, player)
        viewModel.play(p1)
        emitPlaying(player)
        viewModel.togglePlayPause()
        emit(player, PlaybackState.Paused(position = 7.0, duration = 300.0))

        emitPlaying(player)
        player.setPosition(9.0)
        advanceTimeBy(15_000)
        runCurrent()

        assertEquals(listOf("p1" to 7.0, "p1" to 9.0), apiClient.updatePlaybackPositionCalls)
        viewModel.stopPlayback()
    }

    @Test
    fun `android-test-22 shared-session-06 StartingのPausedは送らない`() = runTest {
        // verifies: android-contract-21 / android-test-22
        val p1 = podcast(id = "p1")
        val apiClient = gatedApiClient(listOf(p1))
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.play(p1) // Starting

        emit(player, PlaybackState.Paused(position = 5.0, duration = 300.0))
        advanceTimeBy(15_000)
        runCurrent()

        assertTrue(apiClient.updatePlaybackPositionCalls.isEmpty())
        assertTrue(viewModel.session.value is Active)
    }

    @Test
    fun `android-test-22 shared-session-06 Pausedが続いても2回目は送らない`() = runTest {
        // verifies: android-contract-21 / android-test-22
        val p1 = podcast(id = "p1")
        val apiClient = gatedApiClient(listOf(p1))
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.play(p1)
        emitPlaying(player)
        emit(player, PlaybackState.Paused(position = 7.0, duration = 300.0))

        emit(player, PlaybackState.Paused(position = 8.0, duration = 300.0))

        assertEquals(listOf("p1" to 7.0), apiClient.updatePlaybackPositionCalls)
        viewModel.stopPlayback()
    }

    @Test
    fun `android-test-22 shared-session-06 読込を挟んだ一時停止は送らないがタイマーは止まる`() = runTest {
        // verifies: android-contract-21 / android-test-22
        val p1 = podcast(id = "p1")
        val apiClient = gatedApiClient(listOf(p1))
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.play(p1)
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
        val viewModel = newViewModel(apiClient, player)
        viewModel.play(p1)
        emitPlaying(player)

        emit(player, PlaybackState.Loading)
        player.setPosition(4.0)
        advanceTimeBy(15_000)
        runCurrent()

        assertEquals(listOf("p1" to 4.0), apiClient.updatePlaybackPositionCalls)
        viewModel.stopPlayback()
    }

    // 停止直前の位置は Active からだけ送る（android-deriv-16 の (a)(b) / android-test-23）

    @Test
    fun `android-test-23 android-deriv-16 Startingからの切り替えと停止は送らない`() = runTest {
        // verifies: android-contract-21 / android-test-23
        val (pA, pB) = listOf("pA", "pB").map { podcast(id = it) }
        val apiClient = queueApiClient(pA, pB)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.play(pA) // Starting
        player.setPosition(30.0)

        viewModel.playNow(pB)
        player.setPosition(30.0)
        viewModel.stopPlayback()

        assertTrue(apiClient.updatePlaybackPositionCalls.isEmpty())
    }

    @Test
    fun `android-test-23 android-deriv-16 Completedからの前進の確定もその後の手動の切り替えも完聴の送信のほかは送らない`() = runTest {
        // verifies: android-contract-21 / android-test-23
        val (pA, pB, pC) = listOf("pA", "pB", "pC").map { podcast(id = it) }
        val fetchGate = CompletableDeferred<Unit>()
        val apiClient = gatedApiClient(listOf(pA, pB, pC), fetchGates = mapOf("pB" to fetchGate))
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.playNow(pA)
        viewModel.addToQueue(pB)
        emitPlaying(player)
        emit(player, PlaybackState.Ended(300.0))
        assertEquals(Completed(playable(pA)), viewModel.session.value)

        launch { viewModel.playNow(pC) }
        fetchGate.complete(Unit)
        runCurrent()

        assertEquals(starting(pC), viewModel.session.value)
        assertEquals(listOf("pA" to 300.0), apiClient.updatePlaybackPositionCalls)
        viewModel.stopPlayback()
    }

    // 手動の切り替えのキューの書込は、前の再生の最終同期より前（shared-session-04）

    @Test
    fun `android-test-01 shared-session-04 手動の切り替えで前の再生の最終同期を待つ間に次に再生へ足したepisodeは切り替え先の次に残る`() = runTest {
        val (pA, pB, pX) = listOf("pA", "pB", "pX").map { podcast(id = it) }
        val updateGate = CompletableDeferred<Unit>()
        val apiClient = gatedApiClient(listOf(pA, pB), updateGate = updateGate)
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        cacheManager.cache("pB", byteArrayOf(1)) // 取得を経ない
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player, cacheManager)
        viewModel.playNow(pA)
        viewModel.addToQueue(pB)
        emitPlaying(player)

        launch { viewModel.playNow(pB) }
        runCurrent() // 前の再生（Active の pA）の最終同期の送信で止まる
        viewModel.playNext(pX)
        updateGate.complete(Unit)
        runCurrent()

        assertEquals(listOf("pA", "pB", "pX"), viewModel.queue.value.items.map { it.id })
        assertEquals(pB, viewModel.queue.value.current)
        assertEquals(starting(pB), viewModel.session.value)
        assertSharedInv01(viewModel)
        viewModel.stopPlayback()
    }

    // 主体の離脱での停止（shared-leave-01 / android-test-27）

    @Test
    fun `android-test-27 shared-leave-01 ActiveからstopForSubjectLeaveすると位置を送らずplayerを止めキューを空にする`() = runTest {
        // verifies: shared-leave-01 / android-test-27
        val (pA, pB) = listOf("pA", "pB").map { podcast(id = it) }
        val apiClient = queueApiClient(pA, pB)
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.playNow(pA)
        viewModel.addToQueue(pB)
        emitPlaying(player)
        player.setPosition(42.0)
        val updatesBefore = apiClient.updatePlaybackPositionCalls.toList()
        val stopsBefore = player.stopCallCount

        viewModel.stopForSubjectLeave()
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(stopsBefore + 1, player.stopCallCount)
        assertTrue(viewModel.session.value is NothingPlaying)
        assertNull(viewModel.nowPlaying.value)
        assertNull(viewModel.currentPodcast.value)
        assertTrue(viewModel.queue.value.items.isEmpty())
        assertNull(viewModel.queue.value.current)
        assertEquals(updatesBefore, apiClient.updatePlaybackPositionCalls)
    }

    @Test
    fun `android-test-27 shared-leave-01 取得中の手動の開始は離脱の後に再生しない`() = runTest {
        // verifies: shared-leave-01 / android-test-27
        val pA = podcast(id = "pA")
        val fetchGate = CompletableDeferred<Unit>()
        val apiClient = gatedApiClient(listOf(pA), fetchGates = mapOf("pA" to fetchGate))
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        launch { viewModel.playNow(pA) }
        runCurrent() // pA の取得で止まる

        viewModel.stopForSubjectLeave()
        fetchGate.complete(Unit)
        runCurrent()

        assertTrue(player.prepareCalls.isEmpty())
        assertEquals(0, player.playCallCount)
        assertTrue(viewModel.session.value is NothingPlaying)
        assertTrue(viewModel.queue.value.items.isEmpty())
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
        val viewModel = newViewModel(apiClient, player)
        viewModel.playNow(pA)
        viewModel.addToQueue(pB)
        emitPlaying(player)
        emit(player, PlaybackState.Ended(300.0))
        assertEquals(erroredIdOnly("pB", SessionErrorReason.FetchFailed), viewModel.session.value)
        viewModel.clearError()
        launch { viewModel.retry() }
        runCurrent() // 2 回目の pB の取得で止まる

        viewModel.stopForSubjectLeave()
        retryGate.complete(Unit)
        runCurrent()

        assertEquals(2, pBFetches)
        assertTrue(viewModel.session.value is NothingPlaying)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `android-test-27 shared-leave-01 完聴で始まった前進は離脱の後に次を再生しない`() = runTest {
        // verifies: shared-leave-01 / android-test-27
        val (pA, pB) = listOf("pA", "pB").map { podcast(id = it) }
        val fetchGate = CompletableDeferred<Unit>()
        val apiClient = gatedApiClient(listOf(pA, pB), fetchGates = mapOf("pB" to fetchGate))
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.playNow(pA)
        viewModel.addToQueue(pB)
        emitPlaying(player)
        emit(player, PlaybackState.Ended(300.0)) // 前進が pB の取得で止まる
        assertTrue("pB" in apiClient.fetchPodcastCalls)

        viewModel.stopForSubjectLeave()
        fetchGate.complete(Unit)
        runCurrent()

        assertTrue(player.prepareCalls.none { it == playable(pB).audioUrl })
        assertTrue(viewModel.session.value is NothingPlaying)
    }

    @Test
    fun `android-test-27 shared-leave-01 playMutexを待っていたCACHEDの開始も離脱の後に再生しない`() = runTest {
        // verifies: shared-leave-01 / android-test-27
        val (pA, pC) = listOf("pA", "pC").map { podcast(id = it) }
        val fetchGate = CompletableDeferred<Unit>()
        val apiClient = gatedApiClient(listOf(pA), fetchGates = mapOf("pA" to fetchGate))
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        cacheManager.cache("pC", byteArrayOf(1))
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player, cacheManager)
        launch { viewModel.playNow(pA) }
        launch { viewModel.playNow(pC) }
        runCurrent() // pA が取得で playMutex を保持し、pC は待っている

        viewModel.stopForSubjectLeave()
        fetchGate.complete(Unit)
        runCurrent()

        assertTrue(player.prepareCalls.isEmpty())
        assertTrue(viewModel.session.value is NothingPlaying)
        assertTrue(viewModel.queue.value.items.isEmpty())
    }

    @Test
    fun `android-test-27 shared-leave-01 前の再生の最終同期を待つ間に離脱したら次を再生しない`() = runTest {
        // verifies: shared-leave-01 / android-test-27
        val (pA, pC) = listOf("pA", "pC").map { podcast(id = it) }
        val updateGate = CompletableDeferred<Unit>()
        val apiClient = gatedApiClient(listOf(pA), updateGate = updateGate)
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        cacheManager.cache("pC", byteArrayOf(1))
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player, cacheManager)
        viewModel.playNow(pA)
        emitPlaying(player)
        launch { viewModel.playNow(pC) }
        runCurrent() // 前の再生（Active の pA）の最終同期の送信で止まる

        viewModel.stopForSubjectLeave()
        updateGate.complete(Unit)
        runCurrent()

        assertTrue(player.prepareCalls.none { it == "file:///cache/audio/pC.mp3" })
        assertTrue(viewModel.session.value is NothingPlaying)
        assertTrue(viewModel.queue.value.items.isEmpty())
    }

    @Test
    fun `android-test-27 shared-leave-01 完聴の直後で前進が始まる前に離脱しても次を再生しない`() = runTest {
        // verifies: shared-leave-01 / android-test-27
        val (pA, pB) = listOf("pA", "pB").map { podcast(id = it) }
        val apiClient = gatedApiClient(listOf(pA, pB))
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.playNow(pA)
        viewModel.addToQueue(pB)
        emitPlaying(player)

        player.setState(PlaybackState.Ended(300.0)) // 購読が動く前に離脱を呼ぶ
        viewModel.stopForSubjectLeave()
        runCurrent()

        assertTrue(player.prepareCalls.none { it == playable(pB).audioUrl })
        assertTrue(viewModel.session.value is NothingPlaying)
        assertTrue(viewModel.queue.value.items.isEmpty())
    }

    private fun vocabularyItem(podcastId: String, term: String) = VocabularyItemResponse(
        vocabularyId = "${podcastId}__${term.trim().lowercase()}",
        podcastId = podcastId,
        term = term,
        meaning = "回復力のある",
        example = "The system is resilient.",
        registeredAt = "2026-07-29T03:00:00+00:00",
    )
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
