package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.model.PodcastListResponse
import com.rioikeda.newslisten.model.PodcastResponse
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** `PodcastApiAdapter` は `ApiClient` の 5 操作へ 1 対 1 で委譲し、Podcast 応答だけ decoder を通して `Episode` で返す。 */
class PodcastApiAdapterTest {

    private fun dto(id: String, status: String = "completed", playbackPositionSeconds: Double = 0.0) = PodcastResponse(
        id = id,
        type = "single",
        articleIds = listOf("a1"),
        difficulty = "toeic_600",
        audioUrl = "https://example.com/$id.mp3",
        japaneseIntroText = "イントロ",
        durationSeconds = 120,
        status = status,
        playbackPositionSeconds = playbackPositionSeconds,
        title = "題名 $id",
        createdAt = "2026-07-01T09:00:00+00:00",
    )

    private class RecordingApiClient : BaseFakeApiClient() {
        var listResponse = PodcastListResponse(podcasts = emptyList())
        var fetchPodcastResponse: PodcastResponse? = null
        var fetchPodcastFailure: Throwable? = null
        var updateResponse: PodcastResponse? = null
        var audioBytes = ByteArray(0)

        val fetchedIds = mutableListOf<String>()
        val updates = mutableListOf<Pair<String, Double>>()
        val completedIds = mutableListOf<String>()
        val downloadedUrls = mutableListOf<String>()
        var fetchPodcastsCalls = 0

        override suspend fun fetchPodcasts(): PodcastListResponse {
            fetchPodcastsCalls++
            return listResponse
        }

        override suspend fun fetchPodcast(id: String): PodcastResponse {
            fetchedIds.add(id)
            fetchPodcastFailure?.let { throw it }
            return checkNotNull(fetchPodcastResponse)
        }

        override suspend fun updatePlaybackPosition(id: String, positionSeconds: Double): PodcastResponse {
            updates.add(id to positionSeconds)
            return checkNotNull(updateResponse)
        }

        override suspend fun markCompleted(id: String) {
            completedIds.add(id)
        }

        override suspend fun downloadAudio(url: String): ByteArray {
            downloadedUrls.add(url)
            return audioBytes
        }
    }

    private val client = RecordingApiClient()
    private val adapter = PodcastApiAdapter(client)

    @Test
    fun `fetchPodcastsは一覧を順序を保ってEpisodeへ写す`() = runTest {
        // verifies: CI-A1 / T-A1
        client.listResponse = PodcastListResponse(podcasts = listOf(dto("first", "completed"), dto("second", "processing")))

        val episodes = adapter.fetchPodcasts()

        assertEquals(listOf("first", "second"), episodes.map { it.id })
        assertTrue(episodes[0] is Episode.Playable)
        assertTrue(episodes[1] is Episode.Generating)
        assertEquals(1, client.fetchPodcastsCalls)
    }

    @Test
    fun `fetchPodcastsは空の一覧を空のlistで返す`() = runTest {
        // verifies: CI-A1
        client.listResponse = PodcastListResponse(podcasts = emptyList())

        assertEquals(emptyList<Episode>(), adapter.fetchPodcasts())
    }

    @Test
    fun `fetchPodcastは同じidでApiClientを呼びdecode済みのEpisodeを返す`() = runTest {
        // verifies: CI-A2 / T-A2
        client.fetchPodcastResponse = dto("p1", "failed")

        val episode = adapter.fetchPodcast("p1")

        assertEquals(listOf("p1"), client.fetchedIds)
        assertEquals("p1", episode.id)
        assertTrue("failed は decoder を通って Failed になる: $episode", episode is Episode.Failed)
    }

    @Test
    fun `updatePlaybackPositionは同じ引数でApiClientを呼び返ったDTOをdecodeしたEpisodeを返す`() = runTest {
        // verifies: CI-A3 / T-A3
        client.updateResponse = dto("p1", playbackPositionSeconds = 13.75)

        val episode = adapter.updatePlaybackPosition("p1", 12.5)

        assertEquals(listOf("p1" to 12.5), client.updates)
        assertEquals("p1", episode.id)
        assertEquals("返った DTO の位置が resumeHintSeconds になる", 13.75, episode.resumeHintSeconds, 0.0)
    }

    @Test
    fun `markCompletedは同じidでApiClientを1回呼ぶ`() = runTest {
        // verifies: CI-A4 / T-A4
        adapter.markCompleted("p1")

        assertEquals(listOf("p1"), client.completedIds)
    }

    @Test
    fun `downloadAudioは同じURLでApiClientを呼び同じ配列をそのまま返す`() = runTest {
        // verifies: CI-A5 / T-A5
        client.audioBytes = byteArrayOf(1, 2, 3)

        val bytes = adapter.downloadAudio("u")

        assertEquals(listOf("u"), client.downloadedUrls)
        assertSame(client.audioBytes, bytes)
    }

    @Test
    fun `ApiClientが投げた例外は捕捉も変換もせずそのまま伝播する`() = runTest {
        // verifies: CI-A6 / T-A6
        val failure = IllegalStateException("x")
        client.fetchPodcastFailure = failure

        val thrown = runCatching { adapter.fetchPodcast("p1") }.exceptionOrNull()

        assertSame(failure, thrown)
    }

    @Test
    fun `ApiException_NetworkErrorもそのまま伝播する`() = runTest {
        // verifies: CI-A6
        val failure = ApiException.NetworkError(IOException("offline"))
        client.fetchPodcastFailure = failure

        val thrown = runCatching { adapter.fetchPodcast("p1") }.exceptionOrNull()

        assertSame(failure, thrown)
    }
}
