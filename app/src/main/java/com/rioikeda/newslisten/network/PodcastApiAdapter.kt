package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.catalog.domain.Episode

/**
 * [ApiClient] を包み、Podcast 応答を [EpisodeDecoder] 経由の [Episode] で返す。
 * 未配線で、[PodcastApi] も実装しない（port への接続は android-slice-episode-02）。
 * 例外は捕捉せず透過する。transport の再試行は [OkHttpApiClient] の責務。
 */
internal class PodcastApiAdapter(private val apiClient: ApiClient) {
    suspend fun fetchPodcasts(): List<Episode> =
        apiClient.fetchPodcasts().podcasts.map(EpisodeDecoder::decode)

    suspend fun fetchPodcast(id: String): Episode =
        EpisodeDecoder.decode(apiClient.fetchPodcast(id))

    suspend fun updatePlaybackPosition(id: String, positionSeconds: Double): Episode =
        EpisodeDecoder.decode(apiClient.updatePlaybackPosition(id, positionSeconds))

    suspend fun markCompleted(id: String) {
        apiClient.markCompleted(id)
    }

    suspend fun downloadAudio(url: String): ByteArray = apiClient.downloadAudio(url)
}
