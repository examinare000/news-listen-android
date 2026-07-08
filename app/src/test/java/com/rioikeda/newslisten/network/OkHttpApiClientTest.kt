package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.model.ClientErrorReport
import com.rioikeda.newslisten.model.StarRequest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * [OkHttpApiClient] の挙動検証（MockWebServer）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Networking/APIClient.swift のミラー。
 * - ヘッダ付与: buildRequest（:415-423）
 * - 429/Retry-After: validateResponse（:433-443）
 * - downloadAudio の認証ヘッダ非付与: downloadAudio（:126-137、buildRequest を経由しない構造）
 */
class OkHttpApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ApiClient

    /** テスト内で動的に差し替えるセッショントークン（未ログイン時は null）。 */
    private var token: String? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/")
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(baseUrl, "test-api-key") { token })
            .build()
        client = OkHttpApiClient(baseUrl, okHttpClient)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // --- ヘッダ付与 ---

    @Test
    fun バックエンドへのリクエストにX_API_Keyが付く() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"username":"u","role":"member","display_name":"U"}""")
        )

        client.me()

        assertEquals("test-api-key", server.takeRequest().getHeader("X-API-Key"))
    }

    @Test
    fun tokenProviderが非nullならBearerが付く() = runTest {
        token = "session-token"
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"username":"u","role":"member","display_name":"U"}""")
        )

        client.me()

        assertEquals("Bearer session-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun tokenProviderがnullならBearerが付かない() = runTest {
        token = null
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"username":"u","role":"member","display_name":"U"}""")
        )

        client.me()

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun downloadAudioは別ホスト扱いのURLへ認証ヘッダを付けない() = runTest {
        token = "session-token"
        server.enqueue(MockResponse().setResponseCode(200).setBody("audio-bytes"))

        // 同一サーバー・同一ポートのまま host 文字列だけ変える（別ホスト扱いにする）。
        // MockWebServer を2台立てず、AuthInterceptor が「リクエストURLのhost」で判定している
        // ことをそのまま検証するための最小構成。
        val realUrl = server.url("/audio.mp3")
        val differentHost = if (realUrl.host == "127.0.0.1") "localhost" else "127.0.0.1"
        val externalUrl = realUrl.newBuilder().host(differentHost).build()

        val bytes = client.downloadAudio(externalUrl.toString())

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("X-API-Key"))
        assertNull(recorded.getHeader("Authorization"))
        assertEquals("audio-bytes", String(bytes))
    }

    // --- 429 / Retry-After ---

    @Test
    fun ステータス429でRetryAfterがあればRateLimitedにretryAfterSecondsが入る() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))

        try {
            client.me()
            fail("ApiException.RateLimited が投げられるべき")
        } catch (e: ApiException.RateLimited) {
            assertEquals(120, e.retryAfterSeconds)
        }
    }

    @Test
    fun ステータス429でRetryAfter欠落時はnull() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))

        try {
            client.me()
            fail("ApiException.RateLimited が投げられるべき")
        } catch (e: ApiException.RateLimited) {
            assertNull(e.retryAfterSeconds)
        }
    }

    // --- 202 ---

    @Test
    fun starArticleは202応答でActionResponseを返す() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(202)
                .setBody("""{"status":"ok","article_id":"a1","remaining":3}""")
        )

        val response = client.starArticle("a1", StarRequest())

        assertEquals("ok", response.status)
        assertEquals("a1", response.articleId)
        assertEquals(3, response.remaining)
    }

    // --- updatePlaybackPosition ---

    @Test
    fun updatePlaybackPositionはPATCHでposition_secondsボディを送りPodcastResponseを受ける() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "id":"p1","type":"single","article_ids":["a1"],"difficulty":"toeic_600",
                  "audio_url":"https://example.com/p1.mp3","japanese_intro_text":"intro",
                  "duration_seconds":120,"status":"completed","created_at":"2026-07-01T09:00:00+00:00"
                }
                """.trimIndent()
            )
        )

        val response = client.updatePlaybackPosition("p1", 42.5)

        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertTrue(recorded.body.readUtf8().contains("\"position_seconds\":42.5"))
        assertEquals("p1", response.id)
    }

    // --- エラー系 ---

    @Test
    fun 接続不可時はNetworkErrorを投げる() = runTest {
        server.shutdown()
        try {
            client.me()
            fail("ApiException.NetworkError が投げられるべき")
        } catch (e: ApiException.NetworkError) {
            // 期待どおり
        }
    }

    @Test
    fun HTTP500はHttpErrorを投げる() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        try {
            client.me()
            fail("ApiException.HttpError が投げられるべき")
        } catch (e: ApiException.HttpError) {
            assertEquals(500, e.code)
        }
    }

    @Test
    fun 不正JSONはDecodingErrorを投げる() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not-json"))

        try {
            client.me()
            fail("ApiException.DecodingError が投げられるべき")
        } catch (e: ApiException.DecodingError) {
            // 期待どおり
        }
    }

    // --- fetchFeed のクエリ ---

    @Test
    fun fetchFeedはfilterクエリを付与する() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"articles":[],"date":"2026-07-01"}"""))

        client.fetchFeed("unread")

        assertEquals("/feed?filter=unread", server.takeRequest().path)
    }

    // --- registerDeviceToken / unregisterDeviceToken（フェーズ9・FCM） ---

    @Test
    fun registerDeviceTokenはPOSTでdevice_tokenとplatformボディを送る() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"status":"registered"}"""))

        client.registerDeviceToken("fcm-token-abc", "android")

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/notifications/device-tokens", recorded.path)
        assertEquals(
            """{"device_token":"fcm-token-abc","platform":"android"}""",
            recorded.body.readUtf8(),
        )
    }

    @Test
    fun registerDeviceTokenはplatform省略時androidを送る() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"status":"registered"}"""))

        client.registerDeviceToken("fcm-token-abc")

        assertTrue(server.takeRequest().body.readUtf8().contains("\"platform\":\"android\""))
    }

    @Test
    fun unregisterDeviceTokenはDELETEでtokenとplatformをクエリに付与する() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"unregistered"}"""))

        client.unregisterDeviceToken("fcm-token-abc", "android")

        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/notifications/device-tokens?token=fcm-token-abc&platform=android", recorded.path)
    }

    // --- フェーズ10 P10 Task1: 設定機能（RSS ソース / おすすめサイト / プリファレンス / クォータ / ストリーク） ---

    @Test
    fun fetchSourcesはGETでRssSourcesResponseを返す() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"sources":[{"name":"NHK","url":"https://example.com/nhk.xml"}]}""")
        )

        val response = client.fetchSources()

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/settings/sources", recorded.path)
        assertEquals("NHK", response.sources[0].name)
    }

    @Test
    fun createSourceはPOSTでnameとurlボディを送る() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"sources":[{"name":"NHK","url":"https://example.com/nhk.xml"}]}""")
        )

        client.createSource("NHK", "https://example.com/nhk.xml")

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/settings/sources", recorded.path)
        assertEquals(
            """{"name":"NHK","url":"https://example.com/nhk.xml"}""",
            recorded.body.readUtf8(),
        )
    }

    @Test
    fun updateSourceはPUTでold_urlを含むボディを送る() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"sources":[]}"""))

        client.updateSource(
            oldUrl = "https://example.com/old.xml",
            name = "NHK",
            url = "https://example.com/nhk.xml",
        )

        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("/settings/sources", recorded.path)
        assertEquals(
            """{"name":"NHK","url":"https://example.com/nhk.xml","old_url":"https://example.com/old.xml"}""",
            recorded.body.readUtf8(),
        )
    }

    @Test
    fun deleteSourceはDELETEでurlをクエリに付与する() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"sources":[]}"""))

        client.deleteSource("https://example.com/nhk.xml")

        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/settings/sources?url=https%3A%2F%2Fexample.com%2Fnhk.xml", recorded.path)
    }

    @Test
    fun fetchFeaturedSitesはGETでFeaturedSitesResponseを返す() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"sites":[{"id":"s1","name":"NHK","url":"https://example.com/nhk.xml"}]}""")
        )

        val response = client.fetchFeaturedSites()

        assertEquals("/settings/featured-sources", server.takeRequest().path)
        assertEquals("s1", response.sites[0].id)
    }

    @Test
    fun updatePreferencesはPUTでdifficultyとspeedのみをsnake_caseで送る() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"default_difficulty":"toeic_600","default_playback_speed":1.25,"digest_enabled":false,"digest_article_count":3}"""
            )
        )

        val response = client.updatePreferences(
            defaultDifficulty = "toeic_600",
            defaultPlaybackSpeed = 1.25,
        )

        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("/settings/preferences", recorded.path)
        assertEquals(
            """{"default_difficulty":"toeic_600","default_playback_speed":1.25}""",
            recorded.body.readUtf8(),
        )
        assertEquals("toeic_600", response.defaultDifficulty)
    }

    @Test
    fun fetchGenerationQuotaはGETでGenerationQuotaResponseを返す() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"limit":20,"used":3,"remaining":17,"reset_at":"2026-07-09T00:00:00+00:00"}""")
        )

        val response = client.fetchGenerationQuota()

        assertEquals("/users/me/generation-quota", server.takeRequest().path)
        assertEquals(17, response.remaining)
    }

    @Test
    fun fetchGenerationQuotaは無制限時にremainingがnull() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"limit":0,"used":5,"remaining":null,"reset_at":"2026-07-09T00:00:00+00:00"}""")
        )

        val response = client.fetchGenerationQuota()

        assertNull(response.remaining)
    }

    @Test
    fun fetchListeningStreakはGETでListeningStreakResponseを返す() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"current_streak_days":3,"today_listened":true,"last_listened_day":"2026-07-08"}""")
        )

        val response = client.fetchListeningStreak()

        assertEquals("/users/me/listening-streak", server.takeRequest().path)
        assertEquals(3, response.currentStreakDays)
        assertTrue(response.todayListened)
    }

    // --- フェーズ11 P11 Task1: アカウント管理 ---

    @Test
    fun updateProfileはPATCHでdisplay_nameボディを送りUserResponseを受ける() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"username":"u","role":"member","display_name":"Alice"}""")
        )

        val response = client.updateProfile("Alice")

        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/auth/me", recorded.path)
        assertEquals("""{"display_name":"Alice"}""", recorded.body.readUtf8())
        assertEquals("Alice", response.displayName)
    }

    @Test
    fun changePasswordはPOSTでcurrent_passwordとnew_passwordボディを送る() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))

        client.changePassword("old-pass", "new-pass")

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/auth/password", recorded.path)
        assertEquals(
            """{"current_password":"old-pass","new_password":"new-pass"}""",
            recorded.body.readUtf8(),
        )
    }

    @Test
    fun changePasswordは現パスワード誤りで400のHttpErrorを投げる() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))

        try {
            client.changePassword("wrong-pass", "new-pass")
            fail("ApiException.HttpError が投げられるべき")
        } catch (e: ApiException.HttpError) {
            assertEquals(400, e.code)
        }
    }

    @Test
    fun changePasswordは新パスワード強度不足で422のHttpErrorを投げる() = runTest {
        server.enqueue(MockResponse().setResponseCode(422))

        try {
            client.changePassword("old-pass", "weak")
            fail("ApiException.HttpError が投げられるべき")
        } catch (e: ApiException.HttpError) {
            assertEquals(422, e.code)
        }
    }

    @Test
    fun listSessionsはGETでSessionsListResponseをデコードする() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "sessions": [
                    {"id":"s1","device_label":null,"created_at":"2026-07-01T09:00:00+00:00","last_used_at":null,"current":true}
                  ]
                }
                """.trimIndent()
            )
        )

        val response = client.listSessions()

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/auth/sessions", recorded.path)
        assertEquals(1, response.sessions.size)
        assertEquals("s1", response.sessions[0].id)
        assertNull(response.sessions[0].deviceLabel)
        assertTrue(response.sessions[0].current)
    }

    @Test
    fun revokeSessionはDELETEで指定IDへ送る() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        client.revokeSession("s1")

        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/auth/sessions/s1", recorded.path)
    }

    @Test
    fun revokeSessionは404でも例外を投げず冪等成功扱いにする() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        // 例外が投げられなければ成功（iOS SessionsViewModel:57 と同じ「既に失効済みは成功扱い」方針）。
        client.revokeSession("already-revoked")
    }

    @Test
    fun revokeSessionは404以外のエラーはHttpErrorを投げる() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        try {
            client.revokeSession("s1")
            fail("ApiException.HttpError が投げられるべき")
        } catch (e: ApiException.HttpError) {
            assertEquals(500, e.code)
        }
    }

    @Test
    fun revokeOtherSessionsはPOSTでRevokeSessionsResponseをデコードする() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"revoked_count":2}"""))

        val response = client.revokeOtherSessions()

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/auth/sessions/revoke-others", recorded.path)
        assertEquals(2, response.revokedCount)
    }

    // --- フェーズ12: クラッシュ/クライアントエラー報告（issue #140） ---

    @Test
    fun reportClientErrorはPOSTでsource_kind_message_contextボディを送る() = runTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"status":"ok"}"""))

        client.reportClientError(
            ClientErrorReport(
                source = "android",
                kind = "crash",
                message = "java.lang.RuntimeException",
                context = mapOf("app_version" to "1.0.0"),
            )
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/client-errors", recorded.path)
        assertEquals(
            """{"source":"android","kind":"crash","message":"java.lang.RuntimeException","context":{"app_version":"1.0.0"}}""",
            recorded.body.readUtf8(),
        )
    }

    @Test
    fun reportClientErrorは202応答を成功として扱う() = runTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"status":"ok"}"""))

        // 例外が投げられなければ成功（202 Accepted を正常応答として扱う）。
        client.reportClientError(ClientErrorReport(source = "android", kind = "crash"))
    }
}
