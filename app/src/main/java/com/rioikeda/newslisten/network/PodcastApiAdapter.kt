package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.catalog.domain.Episode

/**
 * [ApiClient] を包み、Podcast 応答を [EpisodeDecoder] 経由の [Episode] で返す。
 * [PodcastApi] を実装し、`AppContainer` が [PodcastViewModel] へ渡す。
 * 例外は捕捉せず透過する。transport の再試行は [OkHttpApiClient] の責務。
 */
internal class PodcastApiAdapter(private val apiClient: ApiClient) : PodcastApi {
    override suspend fun fetchPodcasts(): List<Episode> =
        apiClient.fetchPodcasts().podcasts.map(EpisodeDecoder::decode)

    override suspend fun fetchPodcast(id: String): Episode =
        EpisodeDecoder.decode(apiClient.fetchPodcast(id))

    override suspend fun updatePlaybackPosition(id: String, positionSeconds: Double): Episode =
        EpisodeDecoder.decode(apiClient.updatePlaybackPosition(id, positionSeconds))

    override suspend fun markCompleted(id: String) {
        apiClient.markCompleted(id)
    }

    override suspend fun downloadAudio(url: String): ByteArray = apiClient.downloadAudio(url)
}
