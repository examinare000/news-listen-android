package com.rioikeda.newslisten.auth

import com.rioikeda.newslisten.model.ActionResponse
import com.rioikeda.newslisten.model.ClientErrorReport
import com.rioikeda.newslisten.model.FeaturedSitesResponse
import com.rioikeda.newslisten.model.FeedResponse
import com.rioikeda.newslisten.model.GenerationQuotaResponse
import com.rioikeda.newslisten.model.ListeningStreakResponse
import com.rioikeda.newslisten.model.LoginResponse
import com.rioikeda.newslisten.model.OnboardingStatusResponse
import com.rioikeda.newslisten.model.PasskeyCredentialsListResponse
import com.rioikeda.newslisten.model.PasskeyOptionsResponse
import com.rioikeda.newslisten.model.PodcastListResponse
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.model.PreferencesResponse
import com.rioikeda.newslisten.model.RevokeSessionsResponse
import com.rioikeda.newslisten.model.RssSourcesResponse
import com.rioikeda.newslisten.model.SessionsListResponse
import com.rioikeda.newslisten.model.StarRequest
import com.rioikeda.newslisten.model.UserResponse
import com.rioikeda.newslisten.network.ApiClient
import kotlinx.serialization.json.JsonObject

/**
 * [AuthViewModel] のテスト専用フェイク。
 *
 * フェーズ3（認証）で使う login/logout/me/fetchPreferences のみ挙動を差し替え可能にする。
 * それ以外のメソッドはこのテストスイートのスコープ外のため、誤って呼ばれた場合は
 * 即座に失敗させて検出できるよう例外を投げる。
 */
class FakeApiClient(
    private val onLogin: suspend (username: String, password: String) -> LoginResponse =
        { _, _ -> error("login is not stubbed") },
    private val onLogout: suspend () -> Unit = {},
    private val onMe: suspend () -> UserResponse = { error("me is not stubbed") },
    private val onFetchPreferences: suspend () -> PreferencesResponse =
        { error("fetchPreferences is not stubbed") },
) : ApiClient {
    /** login が呼ばれた回数。空入力ガードで API 未呼び出しであることの検証に使う。 */
    var loginCallCount = 0
        private set

    override suspend fun login(username: String, password: String): LoginResponse {
        loginCallCount++
        return onLogin(username, password)
    }

    override suspend fun logout() = onLogout()

    override suspend fun me(): UserResponse = onMe()

    override suspend fun fetchFeed(filter: String): FeedResponse =
        error("fetchFeed is out of scope for auth tests")

    override suspend fun starArticle(id: String, request: StarRequest): ActionResponse =
        error("starArticle is out of scope for auth tests")

    override suspend fun dismissArticle(id: String): ActionResponse =
        error("dismissArticle is out of scope for auth tests")

    override suspend fun fetchPodcasts(): PodcastListResponse =
        error("fetchPodcasts is out of scope for auth tests")

    override suspend fun fetchPodcast(id: String): PodcastResponse =
        error("fetchPodcast is out of scope for auth tests")

    override suspend fun updatePlaybackPosition(id: String, positionSeconds: Double): PodcastResponse =
        error("updatePlaybackPosition is out of scope for auth tests")

    override suspend fun fetchPreferences(): PreferencesResponse = onFetchPreferences()

    override suspend fun downloadAudio(url: String): ByteArray =
        error("downloadAudio is out of scope for auth tests")

    override suspend fun registerDeviceToken(token: String, platform: String) =
        error("registerDeviceToken is out of scope for auth tests")

    override suspend fun unregisterDeviceToken(token: String, platform: String) =
        error("unregisterDeviceToken is out of scope for auth tests")

    override suspend fun fetchSources(): RssSourcesResponse =
        error("fetchSources is out of scope for auth tests")

    override suspend fun createSource(name: String, url: String): RssSourcesResponse =
        error("createSource is out of scope for auth tests")

    override suspend fun updateSource(oldUrl: String, name: String, url: String): RssSourcesResponse =
        error("updateSource is out of scope for auth tests")

    override suspend fun deleteSource(url: String): RssSourcesResponse =
        error("deleteSource is out of scope for auth tests")

    override suspend fun fetchFeaturedSites(): FeaturedSitesResponse =
        error("fetchFeaturedSites is out of scope for auth tests")

    override suspend fun updatePreferences(defaultDifficulty: String?, defaultPlaybackSpeed: Double?): PreferencesResponse =
        error("updatePreferences is out of scope for auth tests")

    override suspend fun fetchGenerationQuota(): GenerationQuotaResponse =
        error("fetchGenerationQuota is out of scope for auth tests")

    override suspend fun fetchListeningStreak(): ListeningStreakResponse =
        error("fetchListeningStreak is out of scope for auth tests")

    override suspend fun updateProfile(displayName: String): UserResponse =
        error("updateProfile is out of scope for auth tests")

    override suspend fun changePassword(currentPassword: String, newPassword: String) =
        error("changePassword is out of scope for auth tests")

    override suspend fun listSessions(): SessionsListResponse =
        error("listSessions is out of scope for auth tests")

    override suspend fun revokeSession(id: String) =
        error("revokeSession is out of scope for auth tests")

    override suspend fun revokeOtherSessions(): RevokeSessionsResponse =
        error("revokeOtherSessions is out of scope for auth tests")

    override suspend fun reportClientError(report: ClientErrorReport) =
        error("reportClientError is out of scope for auth tests")

    override suspend fun fetchOnboardingStatus(): OnboardingStatusResponse =
        error("fetchOnboardingStatus is out of scope for auth tests")

    override suspend fun completeOnboarding(): OnboardingStatusResponse =
        error("completeOnboarding is out of scope for auth tests")

    override suspend fun passkeyRegisterOptions(): PasskeyOptionsResponse =
        error("passkeyRegisterOptions is out of scope for auth tests")

    override suspend fun passkeyRegisterVerify(challengeId: String, credential: JsonObject) =
        error("passkeyRegisterVerify is out of scope for auth tests")

    override suspend fun passkeyLoginOptions(username: String?): PasskeyOptionsResponse =
        error("passkeyLoginOptions is out of scope for auth tests")

    override suspend fun passkeyLoginVerify(challengeId: String, credential: JsonObject): LoginResponse =
        error("passkeyLoginVerify is out of scope for auth tests")

    override suspend fun listPasskeyCredentials(): PasskeyCredentialsListResponse =
        error("listPasskeyCredentials is out of scope for auth tests")

    override suspend fun deletePasskeyCredential(credentialId: String) =
        error("deletePasskeyCredential is out of scope for auth tests")
}
