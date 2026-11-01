package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.catalog.domain.EpisodeGlossaryEntry
import com.rioikeda.newslisten.catalog.domain.EpisodeKind
import com.rioikeda.newslisten.catalog.domain.EpisodeQuizQuestion
import com.rioikeda.newslisten.catalog.domain.EpisodeSourceKind
import com.rioikeda.newslisten.catalog.domain.EpisodeTranscriptSegment
import com.rioikeda.newslisten.network.AudioCacheManager
import com.rioikeda.newslisten.network.FakeFileSystem
import com.rioikeda.newslisten.network.StubNetworkMonitor
import com.rioikeda.newslisten.preferences.InMemoryPreferencesStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字幕・語彙・クイズを 2 件ずつ持つ再生可能な Episode。`PodcastResponse` を経由せず domain の工場で作る
 * （NowPlaying の公開面の検証と、読み取り用の写像の検証が同じ値を使う）。
 */
internal fun playableWithContent(id: String): Episode.Playable = Episode.Playable.create(
    id = id,
    kind = EpisodeKind.Single,
    title = "title-$id",
    japaneseIntroText = "intro-$id",
    difficultyCode = "toeic_600",
    durationSeconds = 300,
    createdAt = "2026-07-01T00:00:00Z",
    resumeHintSeconds = 0.0,
    articleIds = listOf("a1"),
    transcript = listOf(
        EpisodeTranscriptSegment("A", "hello", null),
        EpisodeTranscriptSegment("B", "world", "host"),
    ),
    glossary = listOf(
        EpisodeGlossaryEntry("term1", "意味1", "example1"),
        EpisodeGlossaryEntry("term2", "意味2", "example2"),
    ),
    quiz = listOf(
        EpisodeQuizQuestion("q1", listOf("o1", "o2")),
        EpisodeQuizQuestion("q2", listOf("o3", "o4")),
    ),
    sourceKind = EpisodeSourceKind.Unknown,
    sourceArticles = emptyList(),
    audioUrl = "https://example.com/$id.mp3",
)

/**
 * 公開面 [NowPlaying] の読み取り専用性の検証（android-check-06・android-dec-33）。
 *
 * [NowPlaying] の `segments`・`vocabulary`・`quiz` は [Episode.Playable] の読み取り専用 list をそのまま持つ。
 * 型の上では List でも実体が書き換え可能だと、読み手が cast して `session` の中身まで変えられる。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingEncapsulationTest {

    private suspend fun TestScope.startPlaying(episode: Episode.Playable): PodcastViewModel {
        val apiClient = FakePodcastApiClient(onFetchPodcast = { episode }, onUpdatePlaybackPosition = { _, _ -> episode })
        val viewModel = PodcastViewModel(
            podcastApi = apiClient,
            apiClient = apiClient.learning,
            playerController = FakePlayerController(),
            cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache"),
            networkMonitor = StubNetworkMonitor(initialIsOnline = true),
            dispatcher = StandardTestDispatcher(testScheduler),
            preferencesStore = InMemoryPreferencesStore(),
        )
        viewModel.playNow(episode)
        return viewModel
    }

    /** 書き換えを試みる。読み取り専用なら [UnsupportedOperationException] で弾かれるので握って次へ進む。 */
    private fun <T> attemptWrite(list: List<T>?, extra: T) {
        @Suppress("UNCHECKED_CAST")
        val mutable = list as MutableList<T>
        try { mutable.add(extra) } catch (_: UnsupportedOperationException) { /* 期待どおり */ }
        try { mutable.clear() } catch (_: UnsupportedOperationException) { /* 期待どおり */ }
    }

    /** 書き換えの試みの前後で、nowPlaying の値も session の中の Episode の値も変わらない。 */
    private suspend fun <T> TestScope.assertWriteIsRejected(
        select: (NowPlaying) -> List<T>?,
        selectFromEpisode: (Episode.Playable) -> List<T>?,
        extra: T,
    ) {
        val episode = playableWithContent("pA")
        val viewModel = startPlaying(episode)
        val before = viewModel.nowPlaying.value
        assertNotNull(before)
        val snapshot = select(before!!)!!.toList()
        assertTrue("空 list への寄せでは書き換えの検査にならない", snapshot.isNotEmpty())
        assertEquals(selectFromEpisode(episode), select(before))

        attemptWrite(select(before), extra)

        assertEquals(snapshot, select(viewModel.nowPlaying.value!!))
        assertEquals(snapshot, selectFromEpisode((viewModel.session.value as PlaybackSession.Starting).episode))
        viewModel.stopPlayback()
    }

    @Test
    fun `android-check-06 nowPlaying の segments を書き換えようとしても次の値と session は変わらない`() = runTest {
        assertWriteIsRejected({ it.segments }, { it.transcript }, EpisodeTranscriptSegment("X", "injected", null))
    }

    @Test
    fun `android-check-06 nowPlaying の vocabulary を書き換えようとしても次の値と session は変わらない`() = runTest {
        assertWriteIsRejected({ it.vocabulary }, { it.glossary }, EpisodeGlossaryEntry("injected", "x", "x"))
    }

    @Test
    fun `android-check-06 nowPlaying の quiz を書き換えようとしても次の値と session は変わらない`() = runTest {
        assertWriteIsRejected({ it.quiz }, { it.quiz }, EpisodeQuizQuestion("injected", listOf("x")))
    }
}
