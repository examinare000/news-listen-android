package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.model.ActionResponse
import com.rioikeda.newslisten.model.ClientErrorReport
import com.rioikeda.newslisten.model.DeleteVocabularyResponse
import com.rioikeda.newslisten.model.FeaturedSitesResponse
import com.rioikeda.newslisten.model.FeedResponse
import com.rioikeda.newslisten.model.GenerationQuotaResponse
import com.rioikeda.newslisten.model.LearningDashboardResponse
import com.rioikeda.newslisten.model.ListeningStreakResponse
import com.rioikeda.newslisten.model.LoginResponse
import com.rioikeda.newslisten.model.OnboardingStatusResponse
import com.rioikeda.newslisten.model.PasskeyCredentialsListResponse
import com.rioikeda.newslisten.model.PasskeyOptionsResponse
import com.rioikeda.newslisten.model.PodcastListResponse
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.model.PreferencesResponse
import com.rioikeda.newslisten.model.QuizAnswerRequest
import com.rioikeda.newslisten.model.QuizAnswerResponse
import com.rioikeda.newslisten.model.RevokeSessionsResponse
import com.rioikeda.newslisten.model.RssSourcesResponse
import com.rioikeda.newslisten.model.SessionsListResponse
import com.rioikeda.newslisten.model.StarRequest
import com.rioikeda.newslisten.model.UserResponse
import com.rioikeda.newslisten.model.VocabularyItemResponse
import com.rioikeda.newslisten.model.VocabularyListResponse
import com.rioikeda.newslisten.model.VocabularyTestResultItemRequest
import com.rioikeda.newslisten.model.VocabularyTestResultResponse
import com.rioikeda.newslisten.model.VocabularyTestSessionResponse
import kotlinx.serialization.json.JsonObject

/**
 * test 側 Fake の基底クラス（CI-T16）。[ApiClient] の全 44 メソッドを `error(...)` で実装し、
 * 派生 Fake が override していないメソッドが呼ばれたら即座に失敗させて検出できるようにする。
 *
 * 戻り値型はすべて明示する。式本体の戻り値型を省くと `Nothing` と推論され、
 * 派生 Fake に残す Unit の override（例: `logout() = onLogout()`）と衝突するため。
 */
abstract class BaseFakeApiClient : ApiClient {
    /** [error] メッセージに使う Fake 名。無名サブクラスでは `simpleName` が `null` になるため fallback する。 */
    private val fakeName: String get() = this::class.simpleName ?: this.javaClass.name

    override suspend fun login(username: String, password: String): LoginResponse = error("login is not stubbed in $fakeName")
    override suspend fun logout(): Unit = error("logout is not stubbed in $fakeName")
    override suspend fun me(): UserResponse = error("me is not stubbed in $fakeName")
    override suspend fun fetchFeed(filter: String): FeedResponse = error("fetchFeed is not stubbed in $fakeName")
    override suspend fun starArticle(id: String, request: StarRequest): ActionResponse = error("starArticle is not stubbed in $fakeName")
    override suspend fun dismissArticle(id: String): ActionResponse = error("dismissArticle is not stubbed in $fakeName")
    override suspend fun fetchPodcasts(): PodcastListResponse = error("fetchPodcasts is not stubbed in $fakeName")
    override suspend fun fetchPodcast(id: String): PodcastResponse = error("fetchPodcast is not stubbed in $fakeName")
    override suspend fun updatePlaybackPosition(id: String, positionSeconds: Double): PodcastResponse = error("updatePlaybackPosition is not stubbed in $fakeName")
    override suspend fun markCompleted(id: String): Unit = error("markCompleted is not stubbed in $fakeName")
    override suspend fun submitQuizAnswers(id: String, request: QuizAnswerRequest): QuizAnswerResponse = error("submitQuizAnswers is not stubbed in $fakeName")
    override suspend fun fetchPreferences(): PreferencesResponse = error("fetchPreferences is not stubbed in $fakeName")
    override suspend fun downloadAudio(url: String): ByteArray = error("downloadAudio is not stubbed in $fakeName")
    override suspend fun registerDeviceToken(token: String, platform: String): Unit = error("registerDeviceToken is not stubbed in $fakeName")
    override suspend fun unregisterDeviceToken(token: String, platform: String): Unit = error("unregisterDeviceToken is not stubbed in $fakeName")
    override suspend fun fetchSources(): RssSourcesResponse = error("fetchSources is not stubbed in $fakeName")
    override suspend fun createSource(name: String, url: String): RssSourcesResponse = error("createSource is not stubbed in $fakeName")
    override suspend fun updateSource(oldUrl: String, name: String, url: String): RssSourcesResponse = error("updateSource is not stubbed in $fakeName")
    override suspend fun deleteSource(url: String): RssSourcesResponse = error("deleteSource is not stubbed in $fakeName")
    override suspend fun fetchFeaturedSites(): FeaturedSitesResponse = error("fetchFeaturedSites is not stubbed in $fakeName")
    override suspend fun updatePreferences(defaultDifficulty: String?, defaultPlaybackSpeed: Double?): PreferencesResponse = error("updatePreferences is not stubbed in $fakeName")
    override suspend fun fetchGenerationQuota(): GenerationQuotaResponse = error("fetchGenerationQuota is not stubbed in $fakeName")
    override suspend fun fetchListeningStreak(): ListeningStreakResponse = error("fetchListeningStreak is not stubbed in $fakeName")
    override suspend fun fetchLearningDashboard(): LearningDashboardResponse = error("fetchLearningDashboard is not stubbed in $fakeName")
    override suspend fun updateWeeklyGoalEpisodes(weeklyGoalEpisodes: Int): PreferencesResponse = error("updateWeeklyGoalEpisodes is not stubbed in $fakeName")
    override suspend fun saveVocabulary(podcastId: String, term: String): VocabularyItemResponse = error("saveVocabulary is not stubbed in $fakeName")
    override suspend fun fetchVocabulary(): VocabularyListResponse = error("fetchVocabulary is not stubbed in $fakeName")
    override suspend fun deleteVocabulary(vocabularyId: String): DeleteVocabularyResponse = error("deleteVocabulary is not stubbed in $fakeName")
    override suspend fun fetchVocabularyTestSession(): VocabularyTestSessionResponse = error("fetchVocabularyTestSession is not stubbed in $fakeName")
    override suspend fun submitVocabularyTestResults(results: List<VocabularyTestResultItemRequest>): VocabularyTestResultResponse = error("submitVocabularyTestResults is not stubbed in $fakeName")
    override suspend fun updateProfile(displayName: String): UserResponse = error("updateProfile is not stubbed in $fakeName")
    override suspend fun changePassword(currentPassword: String, newPassword: String): Unit = error("changePassword is not stubbed in $fakeName")
    override suspend fun listSessions(): SessionsListResponse = error("listSessions is not stubbed in $fakeName")
    override suspend fun revokeSession(id: String): Unit = error("revokeSession is not stubbed in $fakeName")
    override suspend fun revokeOtherSessions(): RevokeSessionsResponse = error("revokeOtherSessions is not stubbed in $fakeName")
    override suspend fun reportClientError(report: ClientErrorReport): Unit = error("reportClientError is not stubbed in $fakeName")
    override suspend fun fetchOnboardingStatus(): OnboardingStatusResponse = error("fetchOnboardingStatus is not stubbed in $fakeName")
    override suspend fun completeOnboarding(): OnboardingStatusResponse = error("completeOnboarding is not stubbed in $fakeName")
    override suspend fun passkeyRegisterOptions(): PasskeyOptionsResponse = error("passkeyRegisterOptions is not stubbed in $fakeName")
    override suspend fun passkeyRegisterVerify(challengeId: String, credential: JsonObject): Unit = error("passkeyRegisterVerify is not stubbed in $fakeName")
    override suspend fun passkeyLoginOptions(username: String?): PasskeyOptionsResponse = error("passkeyLoginOptions is not stubbed in $fakeName")
    override suspend fun passkeyLoginVerify(challengeId: String, credential: JsonObject): LoginResponse = error("passkeyLoginVerify is not stubbed in $fakeName")
    override suspend fun listPasskeyCredentials(): PasskeyCredentialsListResponse = error("listPasskeyCredentials is not stubbed in $fakeName")
    override suspend fun deletePasskeyCredential(credentialId: String): Unit = error("deletePasskeyCredential is not stubbed in $fakeName")
}
