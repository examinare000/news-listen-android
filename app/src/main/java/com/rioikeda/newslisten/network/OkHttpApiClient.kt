package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.model.ActionResponse
import com.rioikeda.newslisten.model.ClientErrorReport
import com.rioikeda.newslisten.model.DeviceTokenRequest
import com.rioikeda.newslisten.model.FeaturedSitesResponse
import com.rioikeda.newslisten.model.FeedResponse
import com.rioikeda.newslisten.model.GenerationQuotaResponse
import com.rioikeda.newslisten.model.ListeningStreakResponse
import com.rioikeda.newslisten.model.LoginRequest
import com.rioikeda.newslisten.model.LoginResponse
import com.rioikeda.newslisten.model.NewsListenJson
import com.rioikeda.newslisten.model.OnboardingStatusResponse
import com.rioikeda.newslisten.model.PasswordChangeRequest
import com.rioikeda.newslisten.model.PlaybackPositionRequest
import com.rioikeda.newslisten.model.PodcastListResponse
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.model.PreferencesResponse
import com.rioikeda.newslisten.model.ProfileUpdateRequest
import com.rioikeda.newslisten.model.RevokeSessionsResponse
import com.rioikeda.newslisten.model.RssSourceCreateRequest
import com.rioikeda.newslisten.model.RssSourceUpdateRequest
import com.rioikeda.newslisten.model.RssSourcesResponse
import com.rioikeda.newslisten.model.SessionsListResponse
import com.rioikeda.newslisten.model.StarRequest
import com.rioikeda.newslisten.model.UpdatePreferencesRequest
import com.rioikeda.newslisten.model.UserResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/**
 * OkHttp を使った素朴な [ApiClient] 実装。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Networking/APIClient.swift のミラー
 * （buildRequest/validateResponse 相当のロジックを private helper に集約）。
 *
 * `X-API-Key` / `Authorization` の付与は本クラスの責務ではなく、注入される
 * [okHttpClient] に組み込み済みの [AuthInterceptor] が担う（関心の分離）。
 *
 * @param baseUrl API のベース URL。
 * @param okHttpClient 実通信に使うクライアント（[AuthInterceptor] 等を組み込み済みのものを注入する）。
 */
class OkHttpApiClient(
    private val baseUrl: HttpUrl,
    private val okHttpClient: OkHttpClient,
) : ApiClient {

    override suspend fun login(username: String, password: String): LoginResponse =
        execute(
            buildRequest(
                ApiEndpoint.Login,
                body = LoginRequest(username, password),
                bodySerializer = LoginRequest.serializer(),
            ),
            LoginResponse.serializer(),
        )

    override suspend fun logout() {
        executeVoid(buildRequest(ApiEndpoint.Logout))
    }

    override suspend fun me(): UserResponse =
        execute(buildRequest(ApiEndpoint.Me), UserResponse.serializer())

    override suspend fun fetchFeed(filter: String): FeedResponse =
        execute(
            buildRequest(ApiEndpoint.Feed, queryParams = mapOf("filter" to filter)),
            FeedResponse.serializer(),
        )

    override suspend fun starArticle(id: String, request: StarRequest): ActionResponse =
        execute(
            buildRequest(
                ApiEndpoint.StarArticle(id),
                body = request,
                bodySerializer = StarRequest.serializer(),
            ),
            ActionResponse.serializer(),
        )

    override suspend fun dismissArticle(id: String): ActionResponse =
        execute(buildRequest(ApiEndpoint.DismissArticle(id)), ActionResponse.serializer())

    override suspend fun fetchPodcasts(): PodcastListResponse =
        execute(buildRequest(ApiEndpoint.Podcasts), PodcastListResponse.serializer())

    override suspend fun fetchPodcast(id: String): PodcastResponse =
        execute(buildRequest(ApiEndpoint.Podcast(id)), PodcastResponse.serializer())

    override suspend fun updatePlaybackPosition(id: String, positionSeconds: Double): PodcastResponse =
        execute(
            buildRequest(
                ApiEndpoint.UpdatePlaybackPosition(id),
                body = PlaybackPositionRequest(positionSeconds),
                bodySerializer = PlaybackPositionRequest.serializer(),
            ),
            PodcastResponse.serializer(),
        )

    override suspend fun fetchPreferences(): PreferencesResponse =
        execute(buildRequest(ApiEndpoint.Preferences), PreferencesResponse.serializer())

    override suspend fun downloadAudio(url: String): ByteArray {
        val response = executeCall(Request.Builder().url(url).get().build())
        return response.use {
            validateResponse(response)
            response.body?.bytes() ?: ByteArray(0)
        }
    }

    override suspend fun registerDeviceToken(token: String, platform: String) {
        executeVoid(
            buildRequest(
                ApiEndpoint.RegisterDeviceToken,
                body = DeviceTokenRequest(deviceToken = token, platform = platform),
                bodySerializer = DeviceTokenRequest.serializer(),
            )
        )
    }

    override suspend fun unregisterDeviceToken(token: String, platform: String) {
        executeVoid(
            buildRequest(ApiEndpoint.UnregisterDeviceToken, queryParams = mapOf("token" to token, "platform" to platform))
        )
    }

    override suspend fun fetchSources(): RssSourcesResponse =
        execute(buildRequest(ApiEndpoint.FetchSources), RssSourcesResponse.serializer())

    override suspend fun createSource(name: String, url: String): RssSourcesResponse =
        execute(
            buildRequest(
                ApiEndpoint.CreateSource,
                body = RssSourceCreateRequest(name = name, url = url),
                bodySerializer = RssSourceCreateRequest.serializer(),
            ),
            RssSourcesResponse.serializer(),
        )

    override suspend fun updateSource(oldUrl: String, name: String, url: String): RssSourcesResponse =
        execute(
            buildRequest(
                ApiEndpoint.UpdateSource,
                body = RssSourceUpdateRequest(name = name, url = url, oldUrl = oldUrl),
                bodySerializer = RssSourceUpdateRequest.serializer(),
            ),
            RssSourcesResponse.serializer(),
        )

    override suspend fun deleteSource(url: String): RssSourcesResponse =
        execute(
            buildRequest(ApiEndpoint.DeleteSource, queryParams = mapOf("url" to url)),
            RssSourcesResponse.serializer(),
        )

    override suspend fun fetchFeaturedSites(): FeaturedSitesResponse =
        execute(buildRequest(ApiEndpoint.FeaturedSources), FeaturedSitesResponse.serializer())

    override suspend fun updatePreferences(
        defaultDifficulty: String?,
        defaultPlaybackSpeed: Double?,
    ): PreferencesResponse =
        execute(
            buildRequest(
                ApiEndpoint.UpdatePreferences,
                body = UpdatePreferencesRequest(
                    defaultDifficulty = defaultDifficulty,
                    defaultPlaybackSpeed = defaultPlaybackSpeed,
                ),
                bodySerializer = UpdatePreferencesRequest.serializer(),
            ),
            PreferencesResponse.serializer(),
        )

    override suspend fun fetchGenerationQuota(): GenerationQuotaResponse =
        execute(buildRequest(ApiEndpoint.GenerationQuota), GenerationQuotaResponse.serializer())

    override suspend fun fetchListeningStreak(): ListeningStreakResponse =
        execute(buildRequest(ApiEndpoint.ListeningStreak), ListeningStreakResponse.serializer())

    override suspend fun updateProfile(displayName: String): UserResponse =
        execute(
            buildRequest(
                ApiEndpoint.UpdateProfile,
                body = ProfileUpdateRequest(displayName = displayName),
                bodySerializer = ProfileUpdateRequest.serializer(),
            ),
            UserResponse.serializer(),
        )

    override suspend fun changePassword(currentPassword: String, newPassword: String) {
        executeVoid(
            buildRequest(
                ApiEndpoint.ChangePassword,
                body = PasswordChangeRequest(currentPassword = currentPassword, newPassword = newPassword),
                bodySerializer = PasswordChangeRequest.serializer(),
            )
        )
    }

    override suspend fun listSessions(): SessionsListResponse =
        execute(buildRequest(ApiEndpoint.ListSessions), SessionsListResponse.serializer())

    /**
     * 404（既に失効済み）を冪等成功として扱う。iOS SessionsViewModel:57 と同じ方針で、
     * ユーザーが同一セッションを複数タブ等から連打しても UI 上はエラーにしない。
     */
    override suspend fun revokeSession(id: String) {
        try {
            executeVoid(buildRequest(ApiEndpoint.RevokeSession(id)))
        } catch (e: ApiException.HttpError) {
            if (e.code != 404) throw e
        }
    }

    override suspend fun revokeOtherSessions(): RevokeSessionsResponse =
        execute(buildRequest(ApiEndpoint.RevokeOtherSessions), RevokeSessionsResponse.serializer())

    override suspend fun reportClientError(report: ClientErrorReport) {
        executeVoid(
            buildRequest(
                ApiEndpoint.ReportClientError,
                body = report,
                bodySerializer = ClientErrorReport.serializer(),
            )
        )
    }

    override suspend fun fetchOnboardingStatus(): OnboardingStatusResponse =
        execute(buildRequest(ApiEndpoint.OnboardingStatus), OnboardingStatusResponse.serializer())

    override suspend fun completeOnboarding(): OnboardingStatusResponse =
        execute(buildRequest(ApiEndpoint.CompleteOnboarding), OnboardingStatusResponse.serializer())

    // region private helpers

    /** ボディを伴わないエンドポイント用の [Request] を組み立てる。 */
    private fun buildRequest(endpoint: ApiEndpoint, queryParams: Map<String, String> = emptyMap()): Request =
        Request.Builder()
            .url(buildUrl(endpoint.path, queryParams))
            .method(endpoint.method, noBody(endpoint))
            .build()

    /** JSON ボディを伴うエンドポイント用の [Request] を組み立てる。 */
    private fun <TReq> buildRequest(
        endpoint: ApiEndpoint,
        body: TReq,
        bodySerializer: KSerializer<TReq>,
    ): Request {
        val requestBody = NewsListenJson.encodeToString(bodySerializer, body).toRequestBody(JSON_MEDIA_TYPE)
        return Request.Builder().url(buildUrl(endpoint.path, emptyMap())).method(endpoint.method, requestBody).build()
    }

    private fun buildUrl(path: String, queryParams: Map<String, String>): HttpUrl =
        baseUrl.newBuilder()
            .addPathSegments(path.removePrefix("/"))
            .apply { queryParams.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()

    /**
     * ボディなしエンドポイントの [RequestBody] を決める。
     *
     * OkHttp は POST/PATCH 等の body 必須メソッドに `null` を渡すと例外を投げるため、
     * ボディなしエンドポイント（logout/dismiss 等）は空ボディで代替する。GET は `null` のまま。
     */
    private fun noBody(endpoint: ApiEndpoint): RequestBody? =
        if (endpoint.method == "GET") null else ByteArray(0).toRequestBody(null)

    /** リクエストを送りステータス検証後、指定シリアライザでレスポンスボディをデコードして返す。 */
    private suspend fun <T> execute(httpRequest: Request, serializer: KSerializer<T>): T {
        val response = executeCall(httpRequest)
        return response.use {
            validateResponse(response)
            decode(response, serializer)
        }
    }

    /** レスポンスボディを必要としないリクエストを送り、ステータス検証のみ行う。 */
    private suspend fun executeVoid(httpRequest: Request) {
        val response = executeCall(httpRequest)
        response.use { validateResponse(response) }
    }

    /**
     * 非同期 `Call.enqueue()` を使用してリクエストを送り、`IOException` を [ApiException.NetworkError] にラップする。
     * コルーチンキャンセル時は `call.cancel()` で自動的に Call を中止する。
     */
    private suspend fun executeCall(httpRequest: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = okHttpClient.newCall(httpRequest)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    continuation.resumeWith(Result.failure(ApiException.NetworkError(e)))
                }

                override fun onResponse(call: okhttp3.Call, response: Response) {
                    // キャンセル済みの continuation への resume は無視されるため、
                    // 届いた Response を明示的に close しないと接続がリークする。
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    continuation.resumeWith(Result.success(response))
                }
            })
        }

    /** レスポンスボディを [serializer] でデコードし、失敗時は [ApiException.DecodingError] を投げる。 */
    private fun <T> decode(response: Response, serializer: KSerializer<T>): T {
        val bodyString = response.body?.string().orEmpty()
        return try {
            NewsListenJson.decodeFromString(serializer, bodyString)
        } catch (e: SerializationException) {
            throw ApiException.DecodingError(e)
        }
    }

    /**
     * HTTP レスポンスのステータスを検証し、2xx 以外なら例外を投げる。
     *
     * 正本: APIClient.swift:433-443（validateResponse）。429 は Retry-After（秒）を添えて
     * [ApiException.RateLimited] を、それ以外は [ApiException.HttpError] を投げる。
     */
    private fun validateResponse(response: Response) {
        if (response.isSuccessful) return
        if (response.code == 429) {
            val retryAfter = response.header("Retry-After")?.toIntOrNull()
            throw ApiException.RateLimited(retryAfter)
        }
        throw ApiException.HttpError(response.code, response.body?.string())
    }

    // endregion
}
