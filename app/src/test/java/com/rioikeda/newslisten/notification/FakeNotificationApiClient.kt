package com.rioikeda.newslisten.notification

import com.rioikeda.newslisten.model.ActionResponse
import com.rioikeda.newslisten.model.FeaturedSitesResponse
import com.rioikeda.newslisten.model.FeedResponse
import com.rioikeda.newslisten.model.GenerationQuotaResponse
import com.rioikeda.newslisten.model.ListeningStreakResponse
import com.rioikeda.newslisten.model.LoginResponse
import com.rioikeda.newslisten.model.PodcastListResponse
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.model.PreferencesResponse
import com.rioikeda.newslisten.model.RevokeSessionsResponse
import com.rioikeda.newslisten.model.RssSourcesResponse
import com.rioikeda.newslisten.model.SessionsListResponse
import com.rioikeda.newslisten.model.StarRequest
import com.rioikeda.newslisten.model.UserResponse
import com.rioikeda.newslisten.network.ApiClient

/**
 * [FcmTokenRegistrar] のテスト専用フェイク。
 *
 * フェーズ9（プッシュ通知）で使う registerDeviceToken/unregisterDeviceToken のみ挙動を
 * 差し替え可能にする。それ以外はこのテストスイートのスコープ外のため、誤って呼ばれた場合は
 * 即座に失敗させて検出できるよう例外を投げる（auth/feed/podcast の Fake と同じ方針）。
 */
class FakeNotificationApiClient(
    private val onRegisterDeviceToken: suspend (token: String, platform: String) -> Unit = { _, _ -> },
    private val onUnregisterDeviceToken: suspend (token: String, platform: String) -> Unit = { _, _ -> },
) : ApiClient {
    /** registerDeviceToken に渡された (token, platform) の呼び出し履歴。 */
    val registerCalls: MutableList<Pair<String, String>> = mutableListOf()

    /** unregisterDeviceToken に渡された (token, platform) の呼び出し履歴。 */
    val unregisterCalls: MutableList<Pair<String, String>> = mutableListOf()

    override suspend fun registerDeviceToken(token: String, platform: String) {
        registerCalls.add(token to platform)
        onRegisterDeviceToken(token, platform)
    }

    override suspend fun unregisterDeviceToken(token: String, platform: String) {
        unregisterCalls.add(token to platform)
        onUnregisterDeviceToken(token, platform)
    }

    override suspend fun login(username: String, password: String): LoginResponse =
        error("login is out of scope for notification tests")

    override suspend fun logout() = error("logout is out of scope for notification tests")

    override suspend fun me(): UserResponse = error("me is out of scope for notification tests")

    override suspend fun fetchFeed(filter: String): FeedResponse =
        error("fetchFeed is out of scope for notification tests")

    override suspend fun starArticle(id: String, request: StarRequest): ActionResponse =
        error("starArticle is out of scope for notification tests")

    override suspend fun dismissArticle(id: String): ActionResponse =
        error("dismissArticle is out of scope for notification tests")

    override suspend fun fetchPodcasts(): PodcastListResponse =
        error("fetchPodcasts is out of scope for notification tests")

    override suspend fun fetchPodcast(id: String): PodcastResponse =
        error("fetchPodcast is out of scope for notification tests")

    override suspend fun updatePlaybackPosition(id: String, positionSeconds: Double): PodcastResponse =
        error("updatePlaybackPosition is out of scope for notification tests")

    override suspend fun fetchPreferences(): PreferencesResponse =
        error("fetchPreferences is out of scope for notification tests")

    override suspend fun downloadAudio(url: String): ByteArray =
        error("downloadAudio is out of scope for notification tests")

    override suspend fun fetchSources(): RssSourcesResponse =
        error("fetchSources is out of scope for notification tests")

    override suspend fun createSource(name: String, url: String): RssSourcesResponse =
        error("createSource is out of scope for notification tests")

    override suspend fun updateSource(oldUrl: String, name: String, url: String): RssSourcesResponse =
        error("updateSource is out of scope for notification tests")

    override suspend fun deleteSource(url: String): RssSourcesResponse =
        error("deleteSource is out of scope for notification tests")

    override suspend fun fetchFeaturedSites(): FeaturedSitesResponse =
        error("fetchFeaturedSites is out of scope for notification tests")

    override suspend fun updatePreferences(defaultDifficulty: String?, defaultPlaybackSpeed: Double?): PreferencesResponse =
        error("updatePreferences is out of scope for notification tests")

    override suspend fun fetchGenerationQuota(): GenerationQuotaResponse =
        error("fetchGenerationQuota is out of scope for notification tests")

    override suspend fun fetchListeningStreak(): ListeningStreakResponse =
        error("fetchListeningStreak is out of scope for notification tests")

    override suspend fun updateProfile(displayName: String): UserResponse =
        error("updateProfile is out of scope for notification tests")

    override suspend fun changePassword(currentPassword: String, newPassword: String) =
        error("changePassword is out of scope for notification tests")

    override suspend fun listSessions(): SessionsListResponse =
        error("listSessions is out of scope for notification tests")

    override suspend fun revokeSession(id: String) =
        error("revokeSession is out of scope for notification tests")

    override suspend fun revokeOtherSessions(): RevokeSessionsResponse =
        error("revokeOtherSessions is out of scope for notification tests")
}
