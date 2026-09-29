package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.model.PodcastListResponse
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.network.PodcastApi

/**
 * [PodcastApi]（再生 use case が使う狭い port）専用の test double。
 *
 * 既定値・記録フィールドは [FakePodcastApiClient] の同名 5 引数と同じにする
 * （A-S2b で移し替えるときに意味が変わらないようにするため）。
 */
class FakePodcastApi(
    private val onFetchPodcasts: suspend () -> PodcastListResponse =
        { error("fetchPodcasts is not stubbed") },
    private val onFetchPodcast: suspend (id: String) -> PodcastResponse =
        { error("fetchPodcast is not stubbed") },
    private val onUpdatePlaybackPosition: suspend (id: String, positionSeconds: Double) -> PodcastResponse =
        { id, _ -> error("updatePlaybackPosition is not stubbed for id=$id") },
    private val onDownloadAudio: suspend (url: String) -> ByteArray =
        { error("downloadAudio is not stubbed") },
    private val onMarkCompleted: suspend (id: String) -> Unit = {},
) : PodcastApi {
    /** fetchPodcasts が呼ばれた回数。 */
    var fetchPodcastsCallCount = 0
        private set

    /** fetchPodcast に渡された id の呼び出し履歴。 */
    val fetchPodcastCalls: MutableList<String> = mutableListOf()

    /** updatePlaybackPosition に渡された (id, positionSeconds) の呼び出し履歴。 */
    val updatePlaybackPositionCalls: MutableList<Pair<String, Double>> = mutableListOf()

    /** downloadAudio に渡された url の呼び出し履歴。 */
    val downloadAudioCalls: MutableList<String> = mutableListOf()

    /** markCompleted に渡された完聴 ID の呼び出し履歴。 */
    val markCompletedCalls: MutableList<String> = mutableListOf()

    override suspend fun fetchPodcasts(): PodcastListResponse {
        fetchPodcastsCallCount++
        return onFetchPodcasts()
    }

    override suspend fun fetchPodcast(id: String): PodcastResponse {
        fetchPodcastCalls.add(id)
        return onFetchPodcast(id)
    }

    override suspend fun updatePlaybackPosition(id: String, positionSeconds: Double): PodcastResponse {
        updatePlaybackPositionCalls.add(id to positionSeconds)
        return onUpdatePlaybackPosition(id, positionSeconds)
    }

    override suspend fun markCompleted(id: String) {
        markCompletedCalls.add(id)
        onMarkCompleted(id)
    }

    override suspend fun downloadAudio(url: String): ByteArray {
        downloadAudioCalls.add(url)
        return onDownloadAudio(url)
    }
}
