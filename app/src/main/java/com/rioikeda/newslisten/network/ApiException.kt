package com.rioikeda.newslisten.network

/**
 * API 通信で発生しうるエラー。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Networking/APIClient.swift:19-40（APIError）のミラー。
 */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 生成上限など 429 Too Many Requests（Retry-After 秒。iOS APIError.rateLimited 相当）。 */
    class RateLimited(val retryAfterSeconds: Int?) : ApiException("リクエストが多すぎます。しばらくしてからお試しください。")

    /** HTTP ステータスが 2xx 以外だった（429 を除く）。 */
    class HttpError(val code: Int, val bodyMessage: String? = null) : ApiException("HTTP Error $code")

    /** レスポンスボディの JSON デコードに失敗した。 */
    class DecodingError(override val cause: Throwable) : ApiException("Decoding error: ${cause.message}", cause)

    /** ネットワーク層（接続不可・タイムアウト等、`IOException` ラップ）のエラー。 */
    class NetworkError(override val cause: Throwable) : ApiException("Network error: ${cause.message}", cause)
}
