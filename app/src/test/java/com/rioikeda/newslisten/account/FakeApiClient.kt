package com.rioikeda.newslisten.account

import com.rioikeda.newslisten.model.ActionResponse
import com.rioikeda.newslisten.model.ClientErrorReport
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
 * account パッケージ（[SessionsViewModel] / [AccountViewModel]）のテスト専用フェイク。
 *
 * フェーズ11 P11 T4（ログイン中デバイス管理）で使う listSessions/revokeSession/
 * revokeOtherSessions、および T2+T3（表示名更新・パスワード変更）で使う updateProfile/
 * changePassword のみ挙動を差し替え可能にする。それ以外はスコープ外のため、
 * 誤って呼ばれた場合は即座に失敗させて検出できるよう例外を投げる
 * （settings/FakeApiClient.kt・auth/FakeApiClient.kt と同じ設計方針）。
 */
class FakeApiClient(
    private val onListSessions: suspend () -> SessionsListResponse =
        { error("listSessions is not stubbed") },
    private val onRevokeSession: suspend (id: String) -> Unit =
        { error("revokeSession is not stubbed") },
    private val onRevokeOtherSessions: suspend () -> RevokeSessionsResponse =
        { error("revokeOtherSessions is not stubbed") },
    private val onUpdateProfile: suspend (displayName: String) -> UserResponse =
        { error("updateProfile is not stubbed") },
    private val onChangePassword: suspend (currentPassword: String, newPassword: String) -> Unit =
        { _, _ -> error("changePassword is not stubbed") },
) : ApiClient {

    /** [revokeSession] が呼ばれた回数。current セッションでもガードなく呼ばれることの検証に使う。 */
    var revokeSessionCallCount = 0
        private set

    override suspend fun login(username: String, password: String): LoginResponse =
        error("login is out of scope for account tests")

    override suspend fun logout() = error("logout is out of scope for account tests")

    override suspend fun me(): UserResponse = error("me is out of scope for account tests")

    override suspend fun fetchFeed(filter: String): FeedResponse =
        error("fetchFeed is out of scope for account tests")

    override suspend fun starArticle(id: String, request: StarRequest): ActionResponse =
        error("starArticle is out of scope for account tests")

    override suspend fun dismissArticle(id: String): ActionResponse =
        error("dismissArticle is out of scope for account tests")

    override suspend fun fetchPodcasts(): PodcastListResponse =
        error("fetchPodcasts is out of scope for account tests")

    override suspend fun fetchPodcast(id: String): PodcastResponse =
        error("fetchPodcast is out of scope for account tests")

    override suspend fun updatePlaybackPosition(id: String, positionSeconds: Double): PodcastResponse =
        error("updatePlaybackPosition is out of scope for account tests")

    override suspend fun fetchPreferences(): PreferencesResponse =
        error("fetchPreferences is out of scope for account tests")

    override suspend fun downloadAudio(url: String): ByteArray =
        error("downloadAudio is out of scope for account tests")

    override suspend fun registerDeviceToken(token: String, platform: String) =
        error("registerDeviceToken is out of scope for account tests")

    override suspend fun unregisterDeviceToken(token: String, platform: String) =
        error("unregisterDeviceToken is out of scope for account tests")

    override suspend fun fetchSources(): RssSourcesResponse =
        error("fetchSources is out of scope for account tests")

    override suspend fun createSource(name: String, url: String): RssSourcesResponse =
        error("createSource is out of scope for account tests")

    override suspend fun updateSource(oldUrl: String, name: String, url: String): RssSourcesResponse =
        error("updateSource is out of scope for account tests")

    override suspend fun deleteSource(url: String): RssSourcesResponse =
        error("deleteSource is out of scope for account tests")

    override suspend fun fetchFeaturedSites(): FeaturedSitesResponse =
        error("fetchFeaturedSites is out of scope for account tests")

    override suspend fun updatePreferences(defaultDifficulty: String?, defaultPlaybackSpeed: Double?): PreferencesResponse =
        error("updatePreferences is out of scope for account tests")

    override suspend fun fetchGenerationQuota(): GenerationQuotaResponse =
        error("fetchGenerationQuota is out of scope for account tests")

    override suspend fun fetchListeningStreak(): ListeningStreakResponse =
        error("fetchListeningStreak is out of scope for account tests")

    override suspend fun updateProfile(displayName: String): UserResponse =
        onUpdateProfile(displayName)

    override suspend fun changePassword(currentPassword: String, newPassword: String) =
        onChangePassword(currentPassword, newPassword)

    override suspend fun listSessions(): SessionsListResponse = onListSessions()

    override suspend fun revokeSession(id: String) {
        revokeSessionCallCount++
        onRevokeSession(id)
    }

    override suspend fun revokeOtherSessions(): RevokeSessionsResponse = onRevokeOtherSessions()

    override suspend fun reportClientError(report: ClientErrorReport) =
        error("reportClientError is out of scope for account tests")
}
