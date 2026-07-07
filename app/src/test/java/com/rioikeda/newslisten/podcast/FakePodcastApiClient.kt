package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.model.ActionResponse
import com.rioikeda.newslisten.model.FeedResponse
import com.rioikeda.newslisten.model.LoginResponse
import com.rioikeda.newslisten.model.PodcastListResponse
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.model.PreferencesResponse
import com.rioikeda.newslisten.model.StarRequest
import com.rioikeda.newslisten.model.UserResponse
import com.rioikeda.newslisten.network.ApiClient

/**
 * [PodcastViewModel] のテスト専用フェイク。
 *
 * フェーズ5（Podcast 再生）で使う fetchPodcasts/fetchPodcast/updatePlaybackPosition のみ
 * 挙動を差し替え可能にする。それ以外はこのテストスイートのスコープ外のため、
 * 誤って呼ばれた場合は即座に失敗させて検出できるよう例外を投げる（auth/feed の Fake と同じ方針）。
 */
class FakePodcastApiClient(
    private val onFetchPodcasts: suspend () -> PodcastListResponse =
        { error("fetchPodcasts is not stubbed") },
    private val onFetchPodcast: suspend (id: String) -> PodcastResponse =
        { error("fetchPodcast is not stubbed") },
    private val onUpdatePlaybackPosition: suspend (id: String, positionSeconds: Double) -> PodcastResponse =
        { id, _ -> error("updatePlaybackPosition is not stubbed for id=$id") },
    private val onDownloadAudio: suspend (url: String) -> ByteArray =
        { error("downloadAudio is not stubbed") },
) : ApiClient {
    /** fetchPodcasts が呼ばれた回数。 */
    var fetchPodcastsCallCount = 0
        private set

    /** fetchPodcast に渡された id の呼び出し履歴。 */
    val fetchPodcastCalls: MutableList<String> = mutableListOf()

    /** updatePlaybackPosition に渡された (id, positionSeconds) の呼び出し履歴。 */
    val updatePlaybackPositionCalls: MutableList<Pair<String, Double>> = mutableListOf()

    /** downloadAudio に渡された url の呼び出し履歴。 */
    val downloadAudioCalls: MutableList<String> = mutableListOf()

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

    override suspend fun login(username: String, password: String): LoginResponse =
        error("login is out of scope for podcast tests")

    override suspend fun logout() = error("logout is out of scope for podcast tests")

    override suspend fun me(): UserResponse = error("me is out of scope for podcast tests")

    override suspend fun fetchFeed(filter: String): FeedResponse =
        error("fetchFeed is out of scope for podcast tests")

    override suspend fun starArticle(id: String, request: StarRequest): ActionResponse =
        error("starArticle is out of scope for podcast tests")

    override suspend fun dismissArticle(id: String): ActionResponse =
        error("dismissArticle is out of scope for podcast tests")

    override suspend fun fetchPreferences(): PreferencesResponse =
        error("fetchPreferences is out of scope for podcast tests")

    override suspend fun downloadAudio(url: String): ByteArray {
        downloadAudioCalls.add(url)
        return onDownloadAudio(url)
    }
}
