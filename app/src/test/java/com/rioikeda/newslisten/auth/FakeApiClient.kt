package com.rioikeda.newslisten.auth

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
}
