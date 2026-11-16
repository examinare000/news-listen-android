package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.catalog.app.EpisodeCatalog
import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.learning.app.FakeLearningApi
import com.rioikeda.newslisten.learning.app.LearningCommands
import com.rioikeda.newslisten.learning.app.LearningNotice
import com.rioikeda.newslisten.learning.app.StreakQuery
import com.rioikeda.newslisten.learning.app.testLearningCommands
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.network.AudioCacheManager
import com.rioikeda.newslisten.network.EpisodeDecoder
import com.rioikeda.newslisten.network.FakeFileSystem
import com.rioikeda.newslisten.network.StubNetworkMonitor
import com.rioikeda.newslisten.podcast.app.OfflineLibrary
import com.rioikeda.newslisten.podcast.app.PlaybackCoordinator
import com.rioikeda.newslisten.podcast.app.newPositionWiring
import com.rioikeda.newslisten.podcast.app.PlaybackNotice
import com.rioikeda.newslisten.podcast.app.ResumePrompt
import com.rioikeda.newslisten.podcast.app.FakePositionStore
import com.rioikeda.newslisten.podcast.domain.PlaybackState
import com.rioikeda.newslisten.podcast.domain.PositionRecord
import com.rioikeda.newslisten.podcast.domain.ResumeChoice
import com.rioikeda.newslisten.podcast.ui.PodcastViewModel
import com.rioikeda.newslisten.preferences.InMemoryPreferencesStore
import com.rioikeda.newslisten.preferences.app.PreferencesStore
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PodcastViewModel] の挙動検証（一覧・学習の中継・ダウンロードの中継・`notice` の合成・再生の委譲）。
 *
 * 再生の手順そのもの（開始・キュー・自動前進・位置同期・離脱）の観測は `PlaybackCoordinatorTest` にある（android-dec-09）。
 * 正本: ios/NewsListenApp/NewsListenApp/Podcast/PodcastViewModel.swift のミラー
 * （再生 URL の再取得のみ ADR-009/spec §6.1-2 準拠で意図的に改善）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PodcastViewModelTest {

    // 基点で例外の message が出ていた取得の失敗の文言（android-dec-08）。型とは独立に文字列で持つ。
    private val fetchOfflineMessage = "オフラインです。接続を確認してから、もう一度お試しください"
    private val vocabularyFailedMessage = "語彙の登録に失敗しました"

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

    // Coordinator と OfflineLibrary に同じ lookup を渡す。呼出の直前に使う Episode を登録する
    // （一覧に無い値の再生やダウンロードも、lookup 経由で解決される）。
    private val lookup = FakeEpisodeLookup()

    private suspend fun PodcastViewModel.playNow(episode: Episode) {
        lookup.register(episode)
        playNow(episode.id)
    }

    private suspend fun PodcastViewModel.playNext(episode: Episode) {
        lookup.register(episode)
        playNext(episode.id)
    }

    private suspend fun PodcastViewModel.addToQueue(episode: Episode) {
        lookup.register(episode)
        addToQueue(episode.id)
    }

    private suspend fun PodcastViewModel.download(episode: Episode) {
        lookup.register(episode)
        download(episode.id)
    }

    // 学習の中継（クイズ・語彙）は LearningCommands が持つ。失敗を起こしたいテストは FakeLearningApi を差し替えて渡す。
    private fun TestScope.learningCommands(api: FakeLearningApi = FakeLearningApi()): LearningCommands =
        testLearningCommands(api, backgroundScope, StandardTestDispatcher(testScheduler))

    private fun TestScope.newViewModel(
        apiClient: FakePodcastApiClient,
        playerController: FakePlayerController,
        cacheManager: AudioCacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache"),
        networkMonitor: StubNetworkMonitor = StubNetworkMonitor(initialIsOnline = true),
        streakQuery: StreakQuery? = null,
        preferencesStore: PreferencesStore = InMemoryPreferencesStore(),
        currentSubject: () -> String? = { "u1" },
        learningCommands: LearningCommands = learningCommands(),
    ): PodcastViewModel {
        val offlineLibrary = OfflineLibrary(cacheManager, apiClient, lookup, currentSubject, StandardTestDispatcher(testScheduler))
        // 現行は 1 つの dispatcher が再生と一覧の両方を直列化する。Coordinator と ViewModel で同じ値を共有して範囲を保つ。
        val dispatcher = StandardTestDispatcher(testScheduler)
        val position = newPositionWiring(apiClient, currentSubject, dispatcher)
        return PodcastViewModel(
            learningCommands = learningCommands,
            coordinator = PlaybackCoordinator(
                podcastApi = apiClient,
                playerController = playerController,
                offlineLibrary = offlineLibrary,
                networkMonitor = networkMonitor,
                preferencesStore = preferencesStore,
                streakQuery = streakQuery,
                dispatcher = dispatcher,
                currentSubject = currentSubject,
                episodeLookup = lookup,
                positionCommands = position.commands,
                positionQueries = position.queries,
            ),
            catalog = EpisodeCatalog(apiClient, dispatcher) { offlineLibrary.syncDownloadedState(it) },
            offlineLibrary = offlineLibrary,
            dispatcher = dispatcher,
        )
    }

    // --- fetchPodcasts ---

    @Test
    fun fetchPodcasts成功でpodcastsが更新される() = runTest {
        val podcasts = listOf(podcast("1"), podcast("2"))
        val apiClient = FakePodcastApiClient(onFetchPodcasts = { podcasts })
        val viewModel = newViewModel(apiClient, FakePlayerController())

        viewModel.fetchPodcasts()

        assertEquals(listOf("1", "2"), viewModel.episodes.value.map { it.row.episodeId })
        assertNull(viewModel.notice.value)
        assertFalse(viewModel.isLoading.value)
    }

    @Test
    fun `android-dec-08 fetchPodcasts失敗でnoticeに反映される`() = runTest {
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        val apiClient = FakePodcastApiClient(onFetchPodcasts = { throw exception })
        val viewModel = newViewModel(apiClient, FakePlayerController())

        viewModel.fetchPodcasts()

        assertEquals(fetchOfflineMessage, viewModel.notice.value?.message)
        assertFalse("例外の message が画面に出る", viewModel.notice.value?.message.orEmpty().contains(exception.message.orEmpty()))
        assertTrue(viewModel.episodes.value.isEmpty())
    }

    @Test
    fun `android-dec-06 NetworkError以外の取得の失敗はVMの合成を通ってFetchFailedの文言になり例外のmessageを出さない`() = runTest {
        val apiClient = FakePodcastApiClient(onFetchPodcasts = { throw ApiException.HttpError(500) })
        val viewModel = newViewModel(apiClient, FakePlayerController())

        viewModel.fetchPodcasts()

        assertEquals(
            "エピソードを取得できませんでした。通信状況を確かめて、もう一度お試しください",
            viewModel.notice.value?.message,
        )
        assertFalse("HTTP Error 500 が画面に出る", viewModel.notice.value?.message.orEmpty().contains("HTTP Error 500"))
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

        viewModel.playNow(podcast(id = "p1"))
        emitPlaying(player)
        viewModel.togglePlayPause() // 一時停止
        emit(player, PlaybackState.Paused(position = 7.0, duration = 300.0))
        player.setPosition(7.0)
        // pause への遷移で Paused.position を 1 回だけ送る。
        assertEquals(listOf("p1" to 7.0), apiClient.updatePlaybackPositionCalls)

        advanceTimeBy(15_000)
        runCurrent()

        assertFalse(viewModel.isPlaying.value)
        assertEquals(listOf("p1" to 7.0), apiClient.updatePlaybackPositionCalls)

        viewModel.stopForSubjectLeave()
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
        viewModel.playNow(podcast(id = "p1"))
        emitPlaying(player)
        assertTrue(viewModel.isPlaying.value)

        viewModel.togglePlayPause()
        emit(player, PlaybackState.Paused(position = 0.0, duration = 300.0))
        assertFalse(viewModel.isPlaying.value)
        assertEquals(1, player.pauseCallCount)

        viewModel.togglePlayPause()
        emitPlaying(player)
        assertTrue(viewModel.isPlaying.value)
        assertEquals(2, player.playCallCount)

        viewModel.stopForSubjectLeave()
    }

    // --- ダウンロード・音声キャッシュ（実体は OfflineLibrary。ここは中継と一覧の同期の観測。フェーズ8-B・ADR-027） ---

    @Test
    fun fetchPodcasts後にキャッシュ済みidがdownloadedIdsへ同期される() = runTest {
        val p1 = podcast(id = "p1")
        val p2 = podcast(id = "p2")
        val fileSystem = FakeFileSystem()
        val cacheManager = AudioCacheManager(fileSystem, baseDir = "/cache")
        cacheManager.cache("u1", "p1", byteArrayOf(1)) // 事前に p1 だけキャッシュ済みの状態を再現する（主体 u1）
        val apiClient = FakePodcastApiClient(onFetchPodcasts = { listOf(p1, p2) })
        val viewModel = newViewModel(apiClient, FakePlayerController(), cacheManager)

        viewModel.fetchPodcasts()

        assertEquals(setOf("p1"), viewModel.downloadedIds.value)
    }

    @Test
    fun `android-dec-02 android-dec-08 downloadはOfflineLibraryへ中継されOfflineLibraryのnoticeがnoticeへ合流する`() = runTest {
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        val apiClient = FakePodcastApiClient(onFetchPodcast = { throw exception })
        val viewModel = newViewModel(apiClient, FakePlayerController())

        viewModel.download(podcast(id = "p1"))

        assertEquals(listOf("p1"), apiClient.fetchPodcastCalls)
        assertEquals(fetchOfflineMessage, viewModel.notice.value?.message)
        assertEquals(emptySet<String>(), viewModel.downloadingIds.value)
    }

    @Test
    fun `android-dec-02 removeDownloadはOfflineLibraryへ中継される`() = runTest {
        val p1 = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(onFetchPodcast = { p1 }, onDownloadAudio = { byteArrayOf(1) })
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val viewModel = newViewModel(apiClient, FakePlayerController(), cacheManager)
        viewModel.download(p1)
        assertTrue(cacheManager.isCached("u1", "p1"))

        viewModel.removeDownload("p1")
        assertFalse(cacheManager.isCached("u1", "p1"))
        assertEquals(emptySet<String>(), viewModel.downloadedIds.value)

        viewModel.removeDownload("../evil")
        assertNotNull(viewModel.notice.value)
    }

    @Test
    fun `android-dec-02 downloadedIdsとdownloadingIdsはOfflineLibraryのものを再公開する`() = runTest {
        val p1 = podcast(id = "p1")
        val gate = CompletableDeferred<Episode>()
        val apiClient = FakePodcastApiClient(onFetchPodcast = { gate.await() }, onDownloadAudio = { byteArrayOf(1) })
        val viewModel = newViewModel(apiClient, FakePlayerController())

        launch { viewModel.download(p1) }
        runCurrent()
        assertEquals(setOf("p1"), viewModel.downloadingIds.value)
        assertEquals(emptySet<String>(), viewModel.downloadedIds.value)

        gate.complete(p1)
        runCurrent()

        assertEquals(emptySet<String>(), viewModel.downloadingIds.value)
        assertEquals(setOf("p1"), viewModel.downloadedIds.value)
    }

    @Test
    fun `android-dec-02 android-dec-08 呼び手がcancelされてもdownloadの失敗はnoticeへ合流する`() = runTest {
        // PodcastScreen の rememberCoroutineScope はタブ移動で cancel される。中継と写しは呼び手と独立に完走する。
        val gate = CompletableDeferred<Episode>()
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        val apiClient = FakePodcastApiClient(onFetchPodcast = { gate.await() })
        val viewModel = newViewModel(apiClient, FakePlayerController())

        val caller = launch { viewModel.download(podcast(id = "p1")) }
        runCurrent()
        assertEquals(setOf("p1"), viewModel.downloadingIds.value)

        caller.cancel()
        runCurrent()
        gate.completeExceptionally(exception)
        runCurrent()

        assertEquals(fetchOfflineMessage, viewModel.notice.value?.message)
        assertEquals(emptySet<String>(), viewModel.downloadingIds.value)
    }

    @Test
    fun `android-dec-02 android-dec-08 並行のdownloadで成功した操作は他の失敗の知らせを再表示しない`() = runTest {
        val x = podcast(id = "X")
        val y = podcast(id = "Y")
        val gateX = CompletableDeferred<Episode>()
        val gateY = CompletableDeferred<Episode>()
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id -> if (id == "X") gateX.await() else gateY.await() },
            onDownloadAudio = { byteArrayOf(1) },
        )
        val viewModel = newViewModel(apiClient, FakePlayerController())

        launch { viewModel.download(x) }
        launch { viewModel.download(y) }
        runCurrent()

        gateY.completeExceptionally(exception)
        runCurrent()
        assertEquals(fetchOfflineMessage, viewModel.notice.value?.message)
        viewModel.dismissNotice()
        assertNull(viewModel.notice.value)

        gateX.complete(x)
        runCurrent()

        assertEquals(setOf("X"), viewModel.downloadedIds.value)
        assertNull(viewModel.notice.value)
    }

    @Test
    fun `android-dec-02 android-dec-08 cancelされた操作の中継は他の失敗の知らせを再表示しない`() = runTest {
        val x = podcast(id = "X")
        val y = podcast(id = "Y")
        val gateX = CompletableDeferred<Episode>()
        val gateY = CompletableDeferred<Episode>()
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { id -> if (id == "X") gateX.await() else gateY.await() },
            onDownloadAudio = { byteArrayOf(1) },
        )
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val offlineLibrary = OfflineLibrary(cacheManager, apiClient, lookup, { "u1" }, StandardTestDispatcher(testScheduler))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val position = newPositionWiring(apiClient, { "u1" }, dispatcher)
        val viewModel = PodcastViewModel(
            learningCommands = learningCommands(),
            coordinator = PlaybackCoordinator(
                podcastApi = apiClient,
                playerController = FakePlayerController(),
                offlineLibrary = offlineLibrary,
                networkMonitor = StubNetworkMonitor(initialIsOnline = true),
                preferencesStore = InMemoryPreferencesStore(),
                streakQuery = null,
                dispatcher = dispatcher,
                currentSubject = { "u1" },
                episodeLookup = lookup,
                positionCommands = position.commands,
                positionQueries = position.queries,
            ),
            catalog = EpisodeCatalog(apiClient, dispatcher) { offlineLibrary.syncDownloadedState(it) },
            offlineLibrary = offlineLibrary,
            dispatcher = dispatcher,
        )

        launch { viewModel.download(x) }
        launch { viewModel.download(y) }
        runCurrent()

        gateY.completeExceptionally(exception)
        runCurrent()
        assertEquals(fetchOfflineMessage, viewModel.notice.value?.message)
        viewModel.dismissNotice()

        offlineLibrary.cancelDownloads("u1")
        runCurrent()
        gateX.complete(x)
        runCurrent()

        assertNull(viewModel.notice.value)
        assertFalse(cacheManager.isCached("u1", "X"))
    }

    /** player の事象を注入し、購読が session へ反映するまで進める（同じ値の連続は届かないので呼び分ける）。 */
    private fun TestScope.emit(player: FakePlayerController, state: PlaybackState) {
        player.setState(state)
        runCurrent()
    }

    private fun TestScope.emitPlaying(player: FakePlayerController) = emit(player, PlaybackState.Playing(0.0, 300.0))

    // --- 再生の操作の委譲と notice の合成（android-dec-09・android-dec-59） ---

    @Test
    fun `android-dec-09 playNowとtogglePlayPauseとseekToはCoordinatorを通ってplayerに届く`() = runTest {
        val p1 = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(onFetchPodcast = { p1 }, onUpdatePlaybackPosition = { _, _ -> p1 })
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)

        viewModel.playNow(p1)
        assertEquals(1, player.prepareCalls.size)
        emitPlaying(player)
        viewModel.togglePlayPause()
        viewModel.seekTo(42.0)

        assertEquals(1, player.pauseCallCount)
        assertEquals(listOf(42.0), player.seekCalls)
        viewModel.stopForSubjectLeave()
    }

    @Test
    fun `android-dec-59 android-dec-71 android-dec-08 noticeは自前の失敗を優先し無ければ再生の失敗を見せdismissNoticeで両方消す`() = runTest {
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        val apiClient = FakePodcastApiClient(onFetchPodcasts = { throw exception })
        val viewModel = newViewModel(apiClient, FakePlayerController())

        viewModel.playNow(podcast(status = "processing")) // 再生の失敗だけがある
        assertEquals("生成中のため再生できません", viewModel.notice.value?.message)

        viewModel.fetchPodcasts() // 自前の失敗が重なると、自前の値が見える
        assertEquals(fetchOfflineMessage, viewModel.notice.value?.message)

        viewModel.dismissNotice() // 自前を消した後に再生の失敗が見えるなら、片方しか消えていない
        assertNull(viewModel.notice.value)
    }

    @Test
    fun `android-dec-59 fetchPodcastsの成功は再生の失敗を消さない`() = runTest {
        val apiClient = FakePodcastApiClient(onFetchPodcasts = { listOf(podcast("1")) })
        val viewModel = newViewModel(apiClient, FakePlayerController())
        viewModel.playNow(podcast(status = "processing"))

        viewModel.fetchPodcasts()

        assertEquals(1, viewModel.episodes.value.size)
        assertEquals("生成中のため再生できません", viewModel.notice.value?.message)
    }

    @Test
    fun `android-dec-59 android-dec-71 android-dec-08 再生の開始の確定は自前の失敗を消さない`() = runTest {
        val p1 = podcast(id = "p1")
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        val apiClient = FakePodcastApiClient(
            onFetchPodcasts = { throw exception },
            onFetchPodcast = { p1 },
            onUpdatePlaybackPosition = { _, _ -> p1 },
        )
        val viewModel = newViewModel(apiClient, FakePlayerController())
        viewModel.fetchPodcasts()
        assertEquals(fetchOfflineMessage, viewModel.notice.value?.message)

        viewModel.playNow(p1)

        assertEquals(fetchOfflineMessage, viewModel.notice.value?.message)
        viewModel.stopForSubjectLeave()
    }

    @Test
    fun `android-dec-59 android-dec-71 android-dec-08 noticeのcollectは合成の値が変わるたびに届く`() = runTest {
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        val apiClient = FakePodcastApiClient(onFetchPodcasts = { throw exception })
        val viewModel = newViewModel(apiClient, FakePlayerController())
        val recorded = mutableListOf<String?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.notice.collect { recorded += it?.message } }
        runCurrent()

        viewModel.playNow(podcast(status = "processing"))
        runCurrent()
        viewModel.fetchPodcasts()
        runCurrent()
        viewModel.dismissNotice()
        runCurrent()

        assertEquals(listOf(null, "生成中のため再生できません", fetchOfflineMessage, null), recorded)
    }

    private fun failingSave(failure: ApiException = ApiException.NetworkError(RuntimeException("save"))) =
        FakeLearningApi().apply { onSaveVocabulary = { _, _ -> throw failure } }

    @Test
    fun `android-dec-63 android-dec-71 android-dec-08 noticeは自前の失敗を最優先しCatalogの失敗が次で再生の失敗が最後でdismissNoticeで合成が消える`() = runTest {
        val fetchFailure = ApiException.NetworkError(RuntimeException("offline"))
        val apiClient = FakePodcastApiClient(onFetchPodcasts = { throw fetchFailure })
        val learning = learningCommands(failingSave())
        val viewModel = newViewModel(apiClient, FakePlayerController(), learningCommands = learning)

        viewModel.playNow(podcast(status = "processing")) // 再生の失敗だけがある
        assertEquals("生成中のため再生できません", viewModel.notice.value?.message)

        viewModel.fetchPodcasts() // Catalog の失敗が重なると、再生の失敗より優先される
        assertEquals(fetchOfflineMessage, viewModel.notice.value?.message)

        learning.registerVocabulary("p1", "term") // 自前の失敗が重なると、自前の値が見える
        val own = viewModel.notice.value
        assertNotNull(own)
        assertTrue(own?.message != fetchOfflineMessage && own?.message != "生成中のため再生できません")

        viewModel.dismissNotice() // 3 つの持ち主のどれか 1 つでも残れば、次の assert で見える
        assertNull(viewModel.notice.value)
    }

    @Test
    fun `android-dec-63 android-dec-71 fetchPodcastsが成功してもVM自身の失敗は消えない`() = runTest {
        val apiClient = FakePodcastApiClient(onFetchPodcasts = { listOf(podcast("1")) })
        val learning = learningCommands(failingSave())
        val viewModel = newViewModel(apiClient, FakePlayerController(), learningCommands = learning)
        learning.registerVocabulary("p1", "term")
        val own = viewModel.notice.value
        assertNotNull(own)

        viewModel.fetchPodcasts()

        assertEquals(own, viewModel.notice.value)
        assertEquals(1, viewModel.episodes.value.size)
    }

    @Test
    fun `android-dec-71 dismissNoticeは3つの持ち主の知らせをそれぞれ消す`() = runTest {
        val apiClient = FakePodcastApiClient(
            onFetchPodcasts = { throw ApiException.NetworkError(RuntimeException("offline")) },
        )
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val offlineLibrary = OfflineLibrary(cacheManager, apiClient, lookup, { "u1" }, StandardTestDispatcher(testScheduler))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val position = newPositionWiring(apiClient, { "u1" }, dispatcher)
        val coordinator = PlaybackCoordinator(
            podcastApi = apiClient,
            playerController = FakePlayerController(),
            offlineLibrary = offlineLibrary,
            networkMonitor = StubNetworkMonitor(initialIsOnline = true),
            preferencesStore = InMemoryPreferencesStore(),
            streakQuery = null,
            dispatcher = dispatcher,
            currentSubject = { "u1" },
            episodeLookup = lookup,
            positionCommands = position.commands,
            positionQueries = position.queries,
        )
        val catalog = EpisodeCatalog(apiClient, dispatcher) { offlineLibrary.syncDownloadedState(it) }
        val learning = learningCommands(failingSave())
        val viewModel = PodcastViewModel(
            learningCommands = learning,
            coordinator = coordinator,
            catalog = catalog,
            offlineLibrary = offlineLibrary,
            dispatcher = dispatcher,
        )
        viewModel.playNow(podcast(status = "processing")) // 再生の持ち主
        viewModel.fetchPodcasts() // Catalog の持ち主
        learning.registerVocabulary("p1", "term") // VM 自身（Learning）
        assertNotNull(coordinator.notice.value)
        assertNotNull(catalog.notice.value)
        assertEquals(vocabularyFailedMessage, viewModel.notice.value?.message)

        viewModel.dismissNotice()

        assertNull(coordinator.notice.value)
        assertNull(catalog.notice.value)
        assertNull(learning.notice.value)
        // 自前の `_notice` は、他の持ち主が全て null の状態で合成が null になることで確かめる
        assertNull(viewModel.notice.value)
    }

    @Test
    fun `語彙の登録の失敗は固定の文言の知らせになりApiExceptionの種類によらず例外のmessageを出さない`() = runTest {
        val learning = learningCommands(failingSave(ApiException.HttpError(500)))
        val viewModel = newViewModel(FakePodcastApiClient(), FakePlayerController(), learningCommands = learning)

        learning.registerVocabulary("p1", "term")

        assertEquals(PlaybackNotice.Learning(LearningNotice.VocabularyFailed), viewModel.notice.value)
        assertEquals(vocabularyFailedMessage, viewModel.notice.value?.message)
    }

    // 保存の失敗（ダウンロード）を起こす。取得と音声は成功し、ディスクの書込だけが失敗する。
    private fun TestScope.saveFailingViewModel(learning: LearningCommands): PodcastViewModel {
        val p1 = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(onFetchPodcast = { p1 }, onDownloadAudio = { byteArrayOf(1) })
        val fileSystem = FakeFileSystem().apply { writeException = java.io.IOException("disk full") }
        return newViewModel(
            apiClient,
            FakePlayerController(),
            cacheManager = AudioCacheManager(fileSystem, baseDir = "/cache"),
            learningCommands = learning,
        )
    }

    @Test
    fun `android-dec-100 語彙の失敗の後にダウンロードの失敗が起きるとダウンロードの知らせが見える`() = runTest {
        val learning = learningCommands(failingSave())
        val viewModel = saveFailingViewModel(learning)
        learning.registerVocabulary("p1", "term")
        assertEquals(vocabularyFailedMessage, viewModel.notice.value?.message)

        viewModel.download(podcast(id = "p1"))

        assertEquals(PlaybackNotice.SaveFailed, viewModel.notice.value)
        assertNull(learning.notice.value)
    }

    @Test
    fun `android-dec-100 ダウンロードの失敗の後に語彙の失敗が起きると語彙の知らせが見える`() = runTest {
        val learning = learningCommands(failingSave())
        val viewModel = saveFailingViewModel(learning)
        viewModel.download(podcast(id = "p1"))
        assertEquals(PlaybackNotice.SaveFailed, viewModel.notice.value)

        learning.registerVocabulary("p1", "term")

        assertEquals(PlaybackNotice.Learning(LearningNotice.VocabularyFailed), viewModel.notice.value)
    }

    @Test
    fun `android-dec-100 dismissNoticeは隠れていた知らせを途中で見せずに消す`() = runTest {
        val learning = learningCommands(failingSave())
        val viewModel = saveFailingViewModel(learning)
        viewModel.download(podcast(id = "p1")) // 自前の `_notice` = SaveFailed
        learning.registerVocabulary("p1", "term") // Learning が優先して見える
        val recorded = mutableListOf<String?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.notice.collect { recorded += it?.message } }
        runCurrent()
        recorded.clear()

        viewModel.dismissNotice()
        runCurrent()

        assertEquals(listOf<String?>(null), recorded)
        assertFalse(recorded.contains("音声を保存できませんでした"))
    }

    @Test
    fun `episodesは一覧と再生中を突き合わせてisNowPlayingを付けcollectは変化ごとに届く`() = runTest {
        val a = podcast("a")
        val b = podcast("b")
        val byId = listOf(a, b).associateBy { it.id }
        val apiClient = FakePodcastApiClient(
            onFetchPodcasts = { listOf(a, b) },
            onFetchPodcast = { id -> byId.getValue(id) },
            onUpdatePlaybackPosition = { id, _ -> byId.getValue(id) },
        )
        val viewModel = newViewModel(apiClient, FakePlayerController())
        val recorded = mutableListOf<Set<String>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.episodes.collect { items -> recorded += items.filter { it.isNowPlaying }.map { it.row.episodeId }.toSet() }
        }
        runCurrent()

        viewModel.fetchPodcasts()
        assertEquals(listOf("a", "b"), viewModel.episodes.value.map { it.row.episodeId })
        assertTrue(viewModel.episodes.value.none { it.isNowPlaying })

        viewModel.playNow(a)
        assertEquals(listOf(true, false), viewModel.episodes.value.map { it.isNowPlaying })

        viewModel.playNow(b)
        assertEquals(listOf(false, true), viewModel.episodes.value.map { it.isNowPlaying })
        runCurrent()

        assertEquals(listOf(emptySet(), setOf("a"), setOf("b")), recorded.distinct())
        viewModel.stopForSubjectLeave()
    }

    // --- 再開の確認（shared-sync-10〜12）---

    /** 再開の確認の中継を見るための組立。既存の [newViewModel] は [com.rioikeda.newslisten.podcast.app.PositionWiring] を捨てるので、Coordinator と store も返す。 */
    private class ResumeWiring(val viewModel: PodcastViewModel, val coordinator: PlaybackCoordinator, val store: FakePositionStore)

    private fun TestScope.newResumeWiring(apiClient: FakePodcastApiClient, playerController: FakePlayerController): ResumeWiring {
        val currentSubject: () -> String? = { "u1" }
        val offlineLibrary = OfflineLibrary(AudioCacheManager(FakeFileSystem(), baseDir = "/cache"), apiClient, lookup, currentSubject, StandardTestDispatcher(testScheduler))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val position = newPositionWiring(apiClient, currentSubject, dispatcher)
        val coordinator = PlaybackCoordinator(
            podcastApi = apiClient,
            playerController = playerController,
            offlineLibrary = offlineLibrary,
            networkMonitor = StubNetworkMonitor(initialIsOnline = true),
            preferencesStore = InMemoryPreferencesStore(),
            streakQuery = null,
            dispatcher = dispatcher,
            currentSubject = currentSubject,
            episodeLookup = lookup,
            positionCommands = position.commands,
            positionQueries = position.queries,
        )
        val viewModel = PodcastViewModel(
            learningCommands = learningCommands(),
            coordinator = coordinator,
            catalog = EpisodeCatalog(apiClient, dispatcher) { offlineLibrary.syncDownloadedState(it) },
            offlineLibrary = offlineLibrary,
            dispatcher = dispatcher,
        )
        return ResumeWiring(viewModel, coordinator, position.store)
    }

    /** p1（600 秒）を位置 30 秒で一時停止させ、別の端末の記録（位置 90 秒・10 秒後の時刻）を置く。確認はまだ出していない。 */
    private suspend fun TestScope.givenPausedWithOtherDeviceRecord(player: FakePlayerController): ResumeWiring {
        val p1 = podcast("p1", durationSeconds = 600)
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { p1 },
            onUpdatePlaybackPosition = { _, _ -> p1 },
        )
        val wiring = newResumeWiring(apiClient, player)
        wiring.viewModel.playNow(p1)
        emit(player, PlaybackState.Playing(0.0, 600.0))
        player.setPosition(30.0)
        emit(player, PlaybackState.Paused(30.0, 600.0))
        wiring.store.seed(PositionRecord("u1", "p1", 90.0, Instant.parse("2026-11-16T00:00:10Z"), unsent = false))
        return wiring
    }

    @Test
    fun `android-possync-40 shared-sync-10 ViewModelのresumePromptはCoordinatorの同じinstance`() = runTest {
        val wiring = newResumeWiring(FakePodcastApiClient(), FakePlayerController())

        assertSame(wiring.coordinator.resumePrompt, wiring.viewModel.resumePrompt)
    }

    @Test
    fun `android-possync-40 shared-sync-10 ViewModelのtogglePlayPauseは確認を出す入力で確認を出す`() = runTest {
        val player = FakePlayerController()
        val wiring = givenPausedWithOtherDeviceRecord(player)
        val playsBefore = player.playCallCount

        wiring.viewModel.togglePlayPause()

        assertEquals(ResumePrompt("p1", 30.0, 90.0), wiring.viewModel.resumePrompt.value)
        assertEquals("再生していない", playsBefore, player.playCallCount)
    }

    @Test
    fun `android-possync-46 shared-sync-12 ViewModelのchoosePositionとdismissResumePromptはCoordinatorへ中継する`() = runTest {
        val player = FakePlayerController()
        val wiring = givenPausedWithOtherDeviceRecord(player)
        wiring.viewModel.togglePlayPause()
        assertEquals(ResumePrompt("p1", 30.0, 90.0), wiring.viewModel.resumePrompt.value)

        wiring.viewModel.dismissResumePrompt()

        assertNull(wiring.coordinator.resumePrompt.value)
        assertTrue("閉じても seek しない", player.seekCalls.isEmpty())

        wiring.viewModel.togglePlayPause()
        assertEquals(ResumePrompt("p1", 30.0, 90.0), wiring.viewModel.resumePrompt.value)

        wiring.viewModel.choosePosition(ResumeChoice.OtherDevice)

        assertNull(wiring.coordinator.resumePrompt.value)
        assertEquals(listOf(90.0), player.seekCalls)
    }
}
