package com.rioikeda.newslisten.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ApiEndpoint の path/method 定義の検証。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Networking/APIEndpoint.swift のミラー
 * （フェーズ2 スコープの10エンドポイントのみを対象とする）。
 */
class ApiEndpointTest {

    @Test
    fun loginはPOST_authLogin() {
        assertEquals("/auth/login", ApiEndpoint.Login.path)
        assertEquals("POST", ApiEndpoint.Login.method)
    }

    @Test
    fun logoutはPOST_authLogout() {
        assertEquals("/auth/logout", ApiEndpoint.Logout.path)
        assertEquals("POST", ApiEndpoint.Logout.method)
    }

    @Test
    fun meはGET_authMe() {
        assertEquals("/auth/me", ApiEndpoint.Me.path)
        assertEquals("GET", ApiEndpoint.Me.method)
    }

    @Test
    fun feedはGET_feed() {
        assertEquals("/feed", ApiEndpoint.Feed.path)
        assertEquals("GET", ApiEndpoint.Feed.method)
    }

    @Test
    fun starArticleはPOST_articlesIdStar() {
        val endpoint = ApiEndpoint.StarArticle("a1")
        assertEquals("/articles/a1/star", endpoint.path)
        assertEquals("POST", endpoint.method)
    }

    @Test
    fun dismissArticleはPOST_articlesIdDismiss() {
        val endpoint = ApiEndpoint.DismissArticle("a1")
        assertEquals("/articles/a1/dismiss", endpoint.path)
        assertEquals("POST", endpoint.method)
    }

    @Test
    fun podcastsはGET_podcasts() {
        assertEquals("/podcasts", ApiEndpoint.Podcasts.path)
        assertEquals("GET", ApiEndpoint.Podcasts.method)
    }

    @Test
    fun podcastはGET_podcastsId() {
        val endpoint = ApiEndpoint.Podcast("p1")
        assertEquals("/podcasts/p1", endpoint.path)
        assertEquals("GET", endpoint.method)
    }

    @Test
    fun updatePlaybackPositionはPATCH_podcastsIdPosition() {
        val endpoint = ApiEndpoint.UpdatePlaybackPosition("p1")
        assertEquals("/podcasts/p1/position", endpoint.path)
        assertEquals("PATCH", endpoint.method)
    }

    @Test
    fun preferencesはGET_settingsPreferences() {
        assertEquals("/settings/preferences", ApiEndpoint.Preferences.path)
        assertEquals("GET", ApiEndpoint.Preferences.method)
    }

    @Test
    fun registerDeviceTokenはPOST_notificationsDeviceTokens() {
        assertEquals("/notifications/device-tokens", ApiEndpoint.RegisterDeviceToken.path)
        assertEquals("POST", ApiEndpoint.RegisterDeviceToken.method)
    }

    @Test
    fun unregisterDeviceTokenはDELETE_notificationsDeviceTokens() {
        assertEquals("/notifications/device-tokens", ApiEndpoint.UnregisterDeviceToken.path)
        assertEquals("DELETE", ApiEndpoint.UnregisterDeviceToken.method)
    }

    // --- フェーズ10 P10 Task1: 設定機能 ---

    @Test
    fun fetchSourcesはGET_settingsSources() {
        assertEquals("/settings/sources", ApiEndpoint.FetchSources.path)
        assertEquals("GET", ApiEndpoint.FetchSources.method)
    }

    @Test
    fun createSourceはPOST_settingsSources() {
        assertEquals("/settings/sources", ApiEndpoint.CreateSource.path)
        assertEquals("POST", ApiEndpoint.CreateSource.method)
    }

    @Test
    fun updateSourceはPUT_settingsSources() {
        assertEquals("/settings/sources", ApiEndpoint.UpdateSource.path)
        assertEquals("PUT", ApiEndpoint.UpdateSource.method)
    }

    @Test
    fun deleteSourceはDELETE_settingsSources() {
        assertEquals("/settings/sources", ApiEndpoint.DeleteSource.path)
        assertEquals("DELETE", ApiEndpoint.DeleteSource.method)
    }

    @Test
    fun featuredSourcesはGET_settingsFeaturedSources() {
        assertEquals("/settings/featured-sources", ApiEndpoint.FeaturedSources.path)
        assertEquals("GET", ApiEndpoint.FeaturedSources.method)
    }

    @Test
    fun updatePreferencesはPUT_settingsPreferences() {
        assertEquals("/settings/preferences", ApiEndpoint.UpdatePreferences.path)
        assertEquals("PUT", ApiEndpoint.UpdatePreferences.method)
    }

    @Test
    fun generationQuotaはGET_usersMeGenerationQuota() {
        assertEquals("/users/me/generation-quota", ApiEndpoint.GenerationQuota.path)
        assertEquals("GET", ApiEndpoint.GenerationQuota.method)
    }

    @Test
    fun listeningStreakはGET_usersMeListeningStreak() {
        assertEquals("/users/me/listening-streak", ApiEndpoint.ListeningStreak.path)
        assertEquals("GET", ApiEndpoint.ListeningStreak.method)
    }

    // --- フェーズ11 P11 Task1: アカウント管理 ---

    @Test
    fun updateProfileはPATCH_authMe() {
        assertEquals("/auth/me", ApiEndpoint.UpdateProfile.path)
        assertEquals("PATCH", ApiEndpoint.UpdateProfile.method)
    }

    @Test
    fun changePasswordはPOST_authPassword() {
        assertEquals("/auth/password", ApiEndpoint.ChangePassword.path)
        assertEquals("POST", ApiEndpoint.ChangePassword.method)
    }

    @Test
    fun listSessionsはGET_authSessions() {
        assertEquals("/auth/sessions", ApiEndpoint.ListSessions.path)
        assertEquals("GET", ApiEndpoint.ListSessions.method)
    }

    @Test
    fun revokeSessionはDELETE_authSessionsId() {
        val endpoint = ApiEndpoint.RevokeSession("s1")
        assertEquals("/auth/sessions/s1", endpoint.path)
        assertEquals("DELETE", endpoint.method)
    }

    @Test
    fun revokeOtherSessionsはPOST_authSessionsRevokeOthers() {
        assertEquals("/auth/sessions/revoke-others", ApiEndpoint.RevokeOtherSessions.path)
        assertEquals("POST", ApiEndpoint.RevokeOtherSessions.method)
    }
}
