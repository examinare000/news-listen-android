package com.rioikeda.newslisten.passkey

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
 * Passkey ViewModel 群（[PasskeyRegistrationViewModel]/[PasskeyLoginViewModel]/
 * [PasskeyCredentialsViewModel]）のテスト専用フェイク。
 *
 * このテストスイートで使う6メソッドのみ挙動を差し替え可能にする。それ以外は
 * スコープ外のため、誤って呼ばれた場合は即座に失敗させて検出できるよう例外を投げる
 * （auth/settings 等の既存 Fake と同じ設計方針）。
 */
class FakeApiClient(
    private val onPasskeyRegisterOptions: suspend () -> PasskeyOptionsResponse =
        { error("passkeyRegisterOptions is not stubbed") },
    private val onPasskeyRegisterVerify: suspend (challengeId: String, credential: JsonObject) -> Unit =
        { _, _ -> error("passkeyRegisterVerify is not stubbed") },
    private val onPasskeyLoginOptions: suspend (username: String?) -> PasskeyOptionsResponse =
        { error("passkeyLoginOptions is not stubbed") },
    private val onPasskeyLoginVerify: suspend (challengeId: String, credential: JsonObject) -> LoginResponse =
        { _, _ -> error("passkeyLoginVerify is not stubbed") },
    private val onListPasskeyCredentials: suspend () -> PasskeyCredentialsListResponse =
        { error("listPasskeyCredentials is not stubbed") },
    private val onDeletePasskeyCredential: suspend (credentialId: String) -> Unit =
        { error("deletePasskeyCredential is not stubbed") },
) : ApiClient {

    /** passkeyRegisterVerify に渡された最後の credential（送信内容の検証用）。 */
    var lastRegisterVerifyCredential: JsonObject? = null
        private set

    /** passkeyLoginVerify に渡された最後の credential（送信内容の検証用）。 */
    var lastLoginVerifyCredential: JsonObject? = null
        private set

    override suspend fun login(username: String, password: String): LoginResponse =
        error("login is out of scope for passkey tests")

    override suspend fun logout() = error("logout is out of scope for passkey tests")

    override suspend fun me(): UserResponse = error("me is out of scope for passkey tests")

    override suspend fun fetchFeed(filter: String): FeedResponse =
        error("fetchFeed is out of scope for passkey tests")

    override suspend fun starArticle(id: String, request: StarRequest): ActionResponse =
        error("starArticle is out of scope for passkey tests")

    override suspend fun dismissArticle(id: String): ActionResponse =
        error("dismissArticle is out of scope for passkey tests")

    override suspend fun fetchPodcasts(): PodcastListResponse =
        error("fetchPodcasts is out of scope for passkey tests")

    override suspend fun fetchPodcast(id: String): PodcastResponse =
        error("fetchPodcast is out of scope for passkey tests")

    override suspend fun updatePlaybackPosition(id: String, positionSeconds: Double): PodcastResponse =
        error("updatePlaybackPosition is out of scope for passkey tests")

    override suspend fun fetchPreferences(): PreferencesResponse =
        error("fetchPreferences is out of scope for passkey tests")

    override suspend fun downloadAudio(url: String): ByteArray =
        error("downloadAudio is out of scope for passkey tests")

    override suspend fun registerDeviceToken(token: String, platform: String) =
        error("registerDeviceToken is out of scope for passkey tests")

    override suspend fun unregisterDeviceToken(token: String, platform: String) =
        error("unregisterDeviceToken is out of scope for passkey tests")

    override suspend fun fetchSources(): RssSourcesResponse =
        error("fetchSources is out of scope for passkey tests")

    override suspend fun createSource(name: String, url: String): RssSourcesResponse =
        error("createSource is out of scope for passkey tests")

    override suspend fun updateSource(oldUrl: String, name: String, url: String): RssSourcesResponse =
        error("updateSource is out of scope for passkey tests")

    override suspend fun deleteSource(url: String): RssSourcesResponse =
        error("deleteSource is out of scope for passkey tests")

    override suspend fun fetchFeaturedSites(): FeaturedSitesResponse =
        error("fetchFeaturedSites is out of scope for passkey tests")

    override suspend fun updatePreferences(defaultDifficulty: String?, defaultPlaybackSpeed: Double?): PreferencesResponse =
        error("updatePreferences is out of scope for passkey tests")

    override suspend fun fetchGenerationQuota(): GenerationQuotaResponse =
        error("fetchGenerationQuota is out of scope for passkey tests")

    override suspend fun fetchListeningStreak(): ListeningStreakResponse =
        error("fetchListeningStreak is out of scope for passkey tests")

    override suspend fun updateProfile(displayName: String): UserResponse =
        error("updateProfile is out of scope for passkey tests")

    override suspend fun changePassword(currentPassword: String, newPassword: String) =
        error("changePassword is out of scope for passkey tests")

    override suspend fun listSessions(): SessionsListResponse =
        error("listSessions is out of scope for passkey tests")

    override suspend fun revokeSession(id: String) =
        error("revokeSession is out of scope for passkey tests")

    override suspend fun revokeOtherSessions(): RevokeSessionsResponse =
        error("revokeOtherSessions is out of scope for passkey tests")

    override suspend fun reportClientError(report: ClientErrorReport) =
        error("reportClientError is out of scope for passkey tests")

    override suspend fun fetchOnboardingStatus(): OnboardingStatusResponse =
        error("fetchOnboardingStatus is out of scope for passkey tests")

    override suspend fun completeOnboarding(): OnboardingStatusResponse =
        error("completeOnboarding is out of scope for passkey tests")

    override suspend fun passkeyRegisterOptions(): PasskeyOptionsResponse = onPasskeyRegisterOptions()

    override suspend fun passkeyRegisterVerify(challengeId: String, credential: JsonObject) {
        lastRegisterVerifyCredential = credential
        onPasskeyRegisterVerify(challengeId, credential)
    }

    override suspend fun passkeyLoginOptions(username: String?): PasskeyOptionsResponse =
        onPasskeyLoginOptions(username)

    override suspend fun passkeyLoginVerify(challengeId: String, credential: JsonObject): LoginResponse {
        lastLoginVerifyCredential = credential
        return onPasskeyLoginVerify(challengeId, credential)
    }

    override suspend fun listPasskeyCredentials(): PasskeyCredentialsListResponse = onListPasskeyCredentials()

    override suspend fun deletePasskeyCredential(credentialId: String) = onDeletePasskeyCredential(credentialId)
}
