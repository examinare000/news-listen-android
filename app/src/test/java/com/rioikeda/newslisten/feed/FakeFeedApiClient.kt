package com.rioikeda.newslisten.feed

import com.rioikeda.newslisten.model.ActionResponse
import com.rioikeda.newslisten.model.FeaturedSitesResponse
import com.rioikeda.newslisten.model.FeedResponse
import com.rioikeda.newslisten.model.GenerationQuotaResponse
import com.rioikeda.newslisten.model.ListeningStreakResponse
import com.rioikeda.newslisten.model.LoginResponse
import com.rioikeda.newslisten.model.PodcastListResponse
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.model.PreferencesResponse
import com.rioikeda.newslisten.model.RssSourcesResponse
import com.rioikeda.newslisten.model.StarRequest
import com.rioikeda.newslisten.model.UserResponse
import com.rioikeda.newslisten.network.ApiClient

/**
 * [FeedViewModel] のテスト専用フェイク。
 *
 * フェーズ4（フィード）で使う fetchFeed/starArticle/dismissArticle のみ挙動を差し替え可能にする。
 * それ以外のメソッドはこのテストスイートのスコープ外のため、誤って呼ばれた場合は
 * 即座に失敗させて検出できるよう例外を投げる（auth/FakeApiClient と同じ方針）。
 */
class FakeFeedApiClient(
    private val onFetchFeed: suspend (filter: String) -> FeedResponse =
        { error("fetchFeed is not stubbed") },
    private val onStarArticle: suspend (id: String, request: StarRequest) -> ActionResponse =
        { _, _ -> error("starArticle is not stubbed") },
    private val onDismissArticle: suspend (id: String) -> ActionResponse =
        { error("dismissArticle is not stubbed") },
) : ApiClient {
    /** fetchFeed が呼ばれた回数。 */
    var fetchFeedCallCount = 0
        private set

    /** starArticle に渡された (id, request) の呼び出し履歴。呼び出し回数・引数検証に使う。 */
    val starCalls: MutableList<Pair<String, StarRequest>> = mutableListOf()

    /** dismissArticle に渡された id の呼び出し履歴。 */
    val dismissCalls: MutableList<String> = mutableListOf()

    override suspend fun fetchFeed(filter: String): FeedResponse {
        fetchFeedCallCount++
        return onFetchFeed(filter)
    }

    override suspend fun starArticle(id: String, request: StarRequest): ActionResponse {
        starCalls.add(id to request)
        return onStarArticle(id, request)
    }

    override suspend fun dismissArticle(id: String): ActionResponse {
        dismissCalls.add(id)
        return onDismissArticle(id)
    }

    override suspend fun login(username: String, password: String): LoginResponse =
        error("login is out of scope for feed tests")

    override suspend fun logout() = error("logout is out of scope for feed tests")

    override suspend fun me(): UserResponse = error("me is out of scope for feed tests")

    override suspend fun fetchPodcasts(): PodcastListResponse =
        error("fetchPodcasts is out of scope for feed tests")

    override suspend fun fetchPodcast(id: String): PodcastResponse =
        error("fetchPodcast is out of scope for feed tests")

    override suspend fun updatePlaybackPosition(id: String, positionSeconds: Double): PodcastResponse =
        error("updatePlaybackPosition is out of scope for feed tests")

    override suspend fun fetchPreferences(): PreferencesResponse =
        error("fetchPreferences is out of scope for feed tests")

    override suspend fun downloadAudio(url: String): ByteArray =
        error("downloadAudio is out of scope for feed tests")

    override suspend fun registerDeviceToken(token: String, platform: String) =
        error("registerDeviceToken is out of scope for feed tests")

    override suspend fun unregisterDeviceToken(token: String, platform: String) =
        error("unregisterDeviceToken is out of scope for feed tests")

    override suspend fun fetchSources(): RssSourcesResponse =
        error("fetchSources is out of scope for feed tests")

    override suspend fun createSource(name: String, url: String): RssSourcesResponse =
        error("createSource is out of scope for feed tests")

    override suspend fun updateSource(oldUrl: String, name: String, url: String): RssSourcesResponse =
        error("updateSource is out of scope for feed tests")

    override suspend fun deleteSource(url: String): RssSourcesResponse =
        error("deleteSource is out of scope for feed tests")

    override suspend fun fetchFeaturedSites(): FeaturedSitesResponse =
        error("fetchFeaturedSites is out of scope for feed tests")

    override suspend fun updatePreferences(defaultDifficulty: String?, defaultPlaybackSpeed: Double?): PreferencesResponse =
        error("updatePreferences is out of scope for feed tests")

    override suspend fun fetchGenerationQuota(): GenerationQuotaResponse =
        error("fetchGenerationQuota is out of scope for feed tests")

    override suspend fun fetchListeningStreak(): ListeningStreakResponse =
        error("fetchListeningStreak is out of scope for feed tests")
}
