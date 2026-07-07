package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.model.PodcastListResponse
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.network.ApiException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    ): PodcastResponse = PodcastResponse(
        id = id,
        type = "daily",
        articleIds = listOf("a1"),
        difficulty = "toeic_600",
        audioUrl = audioUrl,
        japaneseIntroText = "intro",
        durationSeconds = 300,
        status = status,
        errorMessage = errorMessage,
        playbackPositionSeconds = playbackPositionSeconds,
        createdAt = "2026-07-01T00:00:00Z",
    )

    private fun TestScope.newViewModel(
        apiClient: FakePodcastApiClient,
        playerController: FakePlayerController,
    ): PodcastViewModel = PodcastViewModel(apiClient, playerController, StandardTestDispatcher(testScheduler))

    // --- fetchPodcasts ---

    @Test
    fun fetchPodcasts成功でpodcastsが更新される() = runTest {
        val podcasts = listOf(podcast("1"), podcast("2"))
        val apiClient = FakePodcastApiClient(onFetchPodcasts = { PodcastListResponse(podcasts) })
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
        player.setPosition(12.5)
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(listOf("p1" to 12.5, "p1" to 12.5), apiClient.updatePlaybackPositionCalls)

        viewModel.stopPlayback()
    }

    @Test
    fun 一時停止中もPATCHで再生位置を同期し続ける() = runTest {
        val fresh = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { fresh },
            onUpdatePlaybackPosition = { _, _ -> fresh },
        )
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)

        viewModel.play(podcast(id = "p1"))
        viewModel.togglePlayPause() // 一時停止
        player.setPosition(7.0)
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
    fun setSpeedは8段階全てがplayerControllerに伝播する() = runTest {
        val fresh = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { fresh },
            onUpdatePlaybackPosition = { _, _ -> fresh },
        )
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.play(podcast(id = "p1"))

        PlaybackConstants.speeds.forEach { viewModel.setSpeed(it) }

        assertEquals(PlaybackConstants.speeds, player.speedCalls)

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
    fun 再生完了イベントでstopPlaybackが呼ばれタイマーが止まる() = runTest {
        val fresh = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { fresh },
            onUpdatePlaybackPosition = { _, _ -> fresh },
        )
        val player = FakePlayerController()
        val viewModel = newViewModel(apiClient, player)
        viewModel.play(podcast(id = "p1"))
        player.setPosition(99.0)

        player.completePlayback()
        runCurrent()

        assertEquals(1, player.stopCallCount)
        assertNull(viewModel.currentPodcast.value)
        assertEquals(listOf("p1" to 99.0), apiClient.updatePlaybackPositionCalls)

        // タイマーは既に止まっているため、時間を進めても追加の PATCH は飛ばない。
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf("p1" to 99.0), apiClient.updatePlaybackPositionCalls)
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
        player.setPosition(20.0)

        // stopPlayback() を挟まず直接 B へ切り替える（play() 冒頭の内部停止処理を経由）。
        viewModel.play(podcast(id = "pB"))

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

        apiClient.updatePlaybackPositionCalls.clear()
        player.setPosition(3.0)
        advanceTimeBy(15_000)
        runCurrent()

        // A 側の同期タイマーが孤児化して生き残っていれば "pA" への PATCH も混ざって観測される。
        assertEquals(listOf("pB" to 3.0), apiClient.updatePlaybackPositionCalls)

        viewModel.stopPlayback()
    }
}
