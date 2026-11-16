package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.podcast.app.PodcastApi
import com.rioikeda.newslisten.podcast.domain.ServerPosition
import java.time.Instant

/**
 * [PodcastApi]（再生 use case が使う狭い port）専用の test double。
 *
 * 既定値・記録フィールドは [FakePodcastApiClient] の同名 5 引数と同じにする
 * （android-slice-playback-02・android-slice-playback-03・android-slice-playback-04 で移し替えるときに意味が変わらないようにするため）。
 */
class FakePodcastApi(
    private val onFetchPodcasts: suspend () -> List<Episode> =
        { error("fetchPodcasts is not stubbed") },
    private val onFetchPodcast: suspend (id: String) -> Episode =
        { error("fetchPodcast is not stubbed") },
    private val onUpdatePlaybackPosition: suspend (id: String, positionSeconds: Double) -> Episode =
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

    /** updatePlaybackPosition に渡された recordedAt の呼び出し履歴（[updatePlaybackPositionCalls] と対応するインデックス）。 */
    val updatePlaybackPositionRecordedAts: MutableList<Instant> = mutableListOf()

    /** downloadAudio に渡された url の呼び出し履歴。 */
    val downloadAudioCalls: MutableList<String> = mutableListOf()

    /** markCompleted に渡された完聴 ID の呼び出し履歴。 */
    val markCompletedCalls: MutableList<String> = mutableListOf()

    override suspend fun fetchPodcasts(): List<Episode> {
        fetchPodcastsCallCount++
        return onFetchPodcasts()
    }

    override suspend fun fetchPodcast(id: String): Episode {
        fetchPodcastCalls.add(id)
        return onFetchPodcast(id)
    }

    override suspend fun updatePlaybackPosition(id: String, positionSeconds: Double, recordedAt: Instant): ServerPosition {
        updatePlaybackPositionCalls.add(id to positionSeconds)
        updatePlaybackPositionRecordedAts.add(recordedAt)
        val episode = onUpdatePlaybackPosition(id, positionSeconds)
        return ServerPosition(episode.resumeHintSeconds, episode.resumeRecordedAt)
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
