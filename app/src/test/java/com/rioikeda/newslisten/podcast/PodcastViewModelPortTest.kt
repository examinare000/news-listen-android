package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.model.PodcastListResponse
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.network.AudioCacheManager
import com.rioikeda.newslisten.network.BaseFakeApiClient
import com.rioikeda.newslisten.network.FakeFileSystem
import com.rioikeda.newslisten.network.StubNetworkMonitor
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PodcastViewModel] の再生5操作が `PodcastApi` 経由で呼ばれることの振る舞い検証（CI-T16）。
 *
 * 正本: android/docs/design/2026-09-16-implementation-spec-playback-auth.md §4 CI-T16。
 */
class PodcastViewModelPortTest {

    private fun podcast(id: String = "p1"): PodcastResponse = PodcastResponse(
        id = id,
        type = "daily",
        articleIds = listOf("a1"),
        difficulty = "toeic_600",
        audioUrl = "https://example.com/$id.mp3",
        japaneseIntroText = "intro",
        durationSeconds = 300,
        status = "completed",
        createdAt = "2026-07-01T00:00:00Z",
    )

    @Test
    fun fetchPodcastsはpodcastApi経由で呼ばれapiClient経由では呼ばれない() = runTest {
        // verifies: CI-T16
        val podcastApi = FakePodcastApi(onFetchPodcasts = { PodcastListResponse(listOf(podcast("p1"))) })
        // 何も stub しない基底を渡す。fetchPodcasts が誤って apiClient 経由で呼ばれたら error で fail する。
        val apiClient = object : BaseFakeApiClient() {}

        val viewModel = PodcastViewModel(
            podcastApi = podcastApi,
            apiClient = apiClient,
            playerController = FakePlayerController(),
            cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache"),
            networkMonitor = StubNetworkMonitor(initialIsOnline = true),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        viewModel.fetchPodcasts()

        assertEquals(1, podcastApi.fetchPodcastsCallCount)
        assertEquals(listOf(podcast("p1")), viewModel.podcasts.value)
        assertNull(viewModel.errorMessage.value)
    }
}
