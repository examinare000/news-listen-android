package com.rioikeda.newslisten.network

import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * [AuthInterceptor] のヘッダ付与ロジックの検証。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Networking/APIClient.swift:415-423（buildRequest）のミラー。
 * iOS は downloadAudio を buildRequest の外で組み立てる構造分離でヘッダ非付与を実現するが、
 * Android は単一 OkHttpClient にグローバル Interceptor を挟む構成のため、
 * リクエスト URL の host が baseUrl の host と一致する場合のみヘッダを付与する
 * 実行時判定であることを、実ネットワークを介さず Interceptor.Chain を直接検証する。
 */
class AuthInterceptorTest {

    /** intercept() が呼ぶ request()/proceed() のみを実装するテスト用 Chain。 */
    private class FakeChain(private val request: Request) : Interceptor.Chain {
        var capturedRequest: Request? = null
            private set

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            capturedRequest = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody(null))
                .build()
        }

        override fun connection() = null
        override fun call(): Call = throw NotImplementedError("このテストでは使用しない")
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain =
            throw NotImplementedError("このテストでは使用しない")

        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain =
            throw NotImplementedError("このテストでは使用しない")

        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain =
            throw NotImplementedError("このテストでは使用しない")
    }

    private val baseUrl = "https://api.example.com/".toHttpUrl()

    @Test
    fun バックエンドホストへのリクエストにX_API_Keyが付く() {
        val interceptor = AuthInterceptor(baseUrl, "secret-key") { null }
        val chain = FakeChain(Request.Builder().url(baseUrl.resolve("feed")!!).get().build())

        interceptor.intercept(chain)

        assertEquals("secret-key", chain.capturedRequest?.header("X-API-Key"))
    }

    @Test
    fun tokenProviderが非nullならBearerが付く() {
        val interceptor = AuthInterceptor(baseUrl, "secret-key") { "token-123" }
        val chain = FakeChain(Request.Builder().url(baseUrl.resolve("feed")!!).get().build())

        interceptor.intercept(chain)

        assertEquals("Bearer token-123", chain.capturedRequest?.header("Authorization"))
    }

    @Test
    fun tokenProviderがnullならBearerが付かない() {
        val interceptor = AuthInterceptor(baseUrl, "secret-key") { null }
        val chain = FakeChain(Request.Builder().url(baseUrl.resolve("feed")!!).get().build())

        interceptor.intercept(chain)

        assertNull(chain.capturedRequest?.header("Authorization"))
    }

    @Test
    fun baseUrl外のホストには認証ヘッダを付けない() {
        val interceptor = AuthInterceptor(baseUrl, "secret-key") { "token-123" }
        val externalUrl = "https://storage.googleapis.com/bucket/file.mp3".toHttpUrl()
        val chain = FakeChain(Request.Builder().url(externalUrl).get().build())

        interceptor.intercept(chain)

        assertNull(chain.capturedRequest?.header("X-API-Key"))
        assertNull(chain.capturedRequest?.header("Authorization"))
    }

    @Test
    fun 同一ホストだがschemeが異なるURLにはヘッダが付かない() {
        val interceptor = AuthInterceptor(baseUrl, "secret-key") { "token-123" }
        // baseUrl は https:// だが、http:// に変更
        val differentSchemeUrl = "http://api.example.com/feed".toHttpUrl()
        val chain = FakeChain(Request.Builder().url(differentSchemeUrl).get().build())

        interceptor.intercept(chain)

        assertNull(chain.capturedRequest?.header("X-API-Key"))
        assertNull(chain.capturedRequest?.header("Authorization"))
    }

    @Test
    fun 同一ホストだがportが異なるURLにはヘッダが付かない() {
        val interceptor = AuthInterceptor(baseUrl, "secret-key") { "token-123" }
        // baseUrl は https://api.example.com (port 443) だが、port 8443 に変更
        val differentPortUrl = "https://api.example.com:8443/feed".toHttpUrl()
        val chain = FakeChain(Request.Builder().url(differentPortUrl).get().build())

        interceptor.intercept(chain)

        assertNull(chain.capturedRequest?.header("X-API-Key"))
        assertNull(chain.capturedRequest?.header("Authorization"))
    }
}
