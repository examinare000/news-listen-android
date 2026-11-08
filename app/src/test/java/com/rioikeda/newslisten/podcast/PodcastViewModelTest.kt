package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.catalog.app.EpisodeCatalog
import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.engagement.ListeningStreakStore
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
import com.rioikeda.newslisten.podcast.app.PlaybackCoordinator
import com.rioikeda.newslisten.podcast.app.PlaybackNotice
import com.rioikeda.newslisten.preferences.InMemoryPreferencesStore
import com.rioikeda.newslisten.preferences.PreferencesStore
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

    private fun TestScope.newViewModel(
        apiClient: FakePodcastApiClient,
        playerController: FakePlayerController,
        cacheManager: AudioCacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache"),
        networkMonitor: StubNetworkMonitor = StubNetworkMonitor(initialIsOnline = true),
        listeningStreakStore: ListeningStreakStore? = null,
        preferencesStore: PreferencesStore = InMemoryPreferencesStore(),
        currentSubject: () -> String? = { "u1" },
    ): PodcastViewModel {
        val offlineLibrary = OfflineLibrary(cacheManager, apiClient, lookup, currentSubject, StandardTestDispatcher(testScheduler))
        // 現行は 1 つの dispatcher が再生と一覧の両方を直列化する。Coordinator と ViewModel で同じ値を共有して範囲を保つ。
        val dispatcher = StandardTestDispatcher(testScheduler)
        return PodcastViewModel(
            apiClient = apiClient.learning,
            coordinator = PlaybackCoordinator(
                podcastApi = apiClient,
                playerController = playerController,
                offlineLibrary = offlineLibrary,
                networkMonitor = networkMonitor,
                preferencesStore = preferencesStore,
                listeningStreakStore = listeningStreakStore,
                dispatcher = dispatcher,
                currentSubject = currentSubject,
                episodeLookup = lookup,
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
        val viewModel = PodcastViewModel(
            apiClient = apiClient.learning,
            coordinator = PlaybackCoordinator(
                podcastApi = apiClient,
                playerController = FakePlayerController(),
                offlineLibrary = offlineLibrary,
                networkMonitor = StubNetworkMonitor(initialIsOnline = true),
                preferencesStore = InMemoryPreferencesStore(),
                listeningStreakStore = null,
                dispatcher = dispatcher,
                currentSubject = { "u1" },
                episodeLookup = lookup,
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
    fun submitQuizAnswersの送信前にnowPlayingが変わっても指定podcastIdで送信する() = runTest {
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

        viewModel.playNow(p1)
        viewModel.playNow(p2)

        val response = viewModel.submitQuizAnswers("p1", listOf(0))

        assertEquals("p1", submittedId)
        assertEquals(p2.id, viewModel.nowPlaying.value?.episodeId)

        viewModel.stopForSubjectLeave()
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
        assertNull(viewModel.notice.value)
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

    private fun vocabularyItem(podcastId: String, term: String) = VocabularyItemResponse(
        vocabularyId = "${podcastId}__${term.trim().lowercase()}",
        podcastId = podcastId,
        term = term,
        meaning = "回復力のある",
        example = "The system is resilient.",
        registeredAt = "2026-07-29T03:00:00+00:00",
    )

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

    @Test
    fun `android-dec-63 android-dec-71 android-dec-08 noticeは自前の失敗を最優先しCatalogの失敗が次で再生の失敗が最後でdismissNoticeで合成が消える`() = runTest {
        val fetchFailure = ApiException.NetworkError(RuntimeException("offline"))
        val apiClient = FakePodcastApiClient(
            onFetchPodcasts = { throw fetchFailure },
            onSaveVocabulary = { _, _ -> throw ApiException.NetworkError(RuntimeException("save")) },
        )
        val viewModel = newViewModel(apiClient, FakePlayerController())

        viewModel.playNow(podcast(status = "processing")) // 再生の失敗だけがある
        assertEquals("生成中のため再生できません", viewModel.notice.value?.message)

        viewModel.fetchPodcasts() // Catalog の失敗が重なると、再生の失敗より優先される
        assertEquals(fetchOfflineMessage, viewModel.notice.value?.message)

        viewModel.saveVocabulary("p1", "term") // 自前の失敗が重なると、自前の値が見える
        val own = viewModel.notice.value
        assertNotNull(own)
        assertTrue(own?.message != fetchOfflineMessage && own?.message != "生成中のため再生できません")

        viewModel.dismissNotice() // 3 つの持ち主のどれか 1 つでも残れば、次の assert で見える
        assertNull(viewModel.notice.value)
    }

    @Test
    fun `android-dec-63 android-dec-71 fetchPodcastsが成功してもVM自身の失敗は消えない`() = runTest {
        val apiClient = FakePodcastApiClient(
            onFetchPodcasts = { listOf(podcast("1")) },
            onSaveVocabulary = { _, _ -> throw ApiException.NetworkError(RuntimeException("save")) },
        )
        val viewModel = newViewModel(apiClient, FakePlayerController())
        viewModel.saveVocabulary("p1", "term")
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
            onSaveVocabulary = { _, _ -> throw ApiException.NetworkError(RuntimeException("save")) },
        )
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val offlineLibrary = OfflineLibrary(cacheManager, apiClient, lookup, { "u1" }, StandardTestDispatcher(testScheduler))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinator = PlaybackCoordinator(
            podcastApi = apiClient,
            playerController = FakePlayerController(),
            offlineLibrary = offlineLibrary,
            networkMonitor = StubNetworkMonitor(initialIsOnline = true),
            preferencesStore = InMemoryPreferencesStore(),
            listeningStreakStore = null,
            dispatcher = dispatcher,
            currentSubject = { "u1" },
            episodeLookup = lookup,
        )
        val catalog = EpisodeCatalog(apiClient, dispatcher) { offlineLibrary.syncDownloadedState(it) }
        val viewModel = PodcastViewModel(
            apiClient = apiClient.learning,
            coordinator = coordinator,
            catalog = catalog,
            offlineLibrary = offlineLibrary,
            dispatcher = dispatcher,
        )
        viewModel.playNow(podcast(status = "processing")) // 再生の持ち主
        viewModel.fetchPodcasts() // Catalog の持ち主
        viewModel.saveVocabulary("p1", "term") // VM 自身
        assertNotNull(coordinator.notice.value)
        assertNotNull(catalog.notice.value)
        assertEquals(vocabularyFailedMessage, viewModel.notice.value?.message)

        viewModel.dismissNotice()

        assertNull(coordinator.notice.value)
        assertNull(catalog.notice.value)
        // VM 自身の値は、他の 2 つが null の状態で合成が null になることで確かめる
        assertNull(viewModel.notice.value)
    }

    @Test
    fun `語彙の登録の失敗は固定の文言の知らせになりApiExceptionの種類によらず例外のmessageを出さない`() = runTest {
        val apiClient = FakePodcastApiClient(
            onSaveVocabulary = { _, _ -> throw ApiException.HttpError(500) },
        )
        val viewModel = newViewModel(apiClient, FakePlayerController())

        viewModel.saveVocabulary("p1", "term")

        assertEquals(PlaybackNotice.VocabularyFailed, viewModel.notice.value)
        assertEquals(vocabularyFailedMessage, viewModel.notice.value?.message)
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
}
