package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.catalog.app.EpisodeCatalog
import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.learning.app.FakeLearningApi
import com.rioikeda.newslisten.learning.app.testLearningCommands
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.network.AudioCacheManager
import com.rioikeda.newslisten.network.EpisodeDecoder
import com.rioikeda.newslisten.network.FakeFileSystem
import com.rioikeda.newslisten.network.StubNetworkMonitor
import com.rioikeda.newslisten.podcast.app.OfflineLibrary
import com.rioikeda.newslisten.podcast.app.PlaybackCoordinator
import com.rioikeda.newslisten.podcast.app.newPositionWiring
import com.rioikeda.newslisten.podcast.ui.PodcastViewModel
import com.rioikeda.newslisten.preferences.InMemoryPreferencesStore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PodcastViewModel] の再生5操作が `PodcastApi` 経由で呼ばれることの振る舞い検証（android-contract-18）。
 *
 * 正本: docs/design/modules/android/2026-09-16-implementation-spec-playback-auth.md §4 android-contract-18。
 */
class PodcastViewModelPortTest {

    private fun podcast(id: String = "p1"): Episode = EpisodeDecoder.decode(
        PodcastResponse(
            id = id,
            type = "daily",
            articleIds = listOf("a1"),
            difficulty = "toeic_600",
            audioUrl = "https://example.com/$id.mp3",
            japaneseIntroText = "intro",
            durationSeconds = 300,
            status = "completed",
            createdAt = "2026-07-01T00:00:00Z",
        ),
    )

    @Test
    fun fetchPodcastsはpodcastApi経由で呼ばれapiClient経由では呼ばれない() = runTest {
        // verifies: android-contract-18
        val podcastApi = FakePodcastApi(onFetchPodcasts = { listOf(podcast("p1")) })
        // PodcastViewModel は ApiClient を受けない。fetchPodcasts は podcastApi だけを通る。
        val offlineLibrary = OfflineLibrary(
            AudioCacheManager(FakeFileSystem(), baseDir = "/cache"),
            podcastApi,
            FakeEpisodeLookup(),
            { null },
            StandardTestDispatcher(testScheduler),
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val position = newPositionWiring(podcastApi, { null }, dispatcher)
        val viewModel = PodcastViewModel(
            learningCommands = testLearningCommands(FakeLearningApi(), backgroundScope, dispatcher),
            coordinator = PlaybackCoordinator(
                podcastApi = podcastApi,
                playerController = FakePlayerController(),
                offlineLibrary = offlineLibrary,
                networkMonitor = StubNetworkMonitor(initialIsOnline = true),
                preferencesStore = InMemoryPreferencesStore(),
                streakQuery = null,
                dispatcher = dispatcher,
                currentSubject = { null },
                episodeLookup = FakeEpisodeLookup(),
                positionCommands = position.commands,
                positionQueries = position.queries,
            ),
            catalog = EpisodeCatalog(podcastApi, dispatcher) { offlineLibrary.syncDownloadedState(it) },
            offlineLibrary = offlineLibrary,
            dispatcher = dispatcher,
        )

        viewModel.fetchPodcasts()

        assertEquals(1, podcastApi.fetchPodcastsCallCount)
        assertEquals(listOf("p1"), viewModel.episodes.value.map { it.row.episodeId })
        assertNull(viewModel.notice.value)
    }
}
