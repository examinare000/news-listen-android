package com.rioikeda.newslisten.network

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
}
