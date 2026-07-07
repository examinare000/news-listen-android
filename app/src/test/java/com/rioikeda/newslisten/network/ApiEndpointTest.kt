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
}
