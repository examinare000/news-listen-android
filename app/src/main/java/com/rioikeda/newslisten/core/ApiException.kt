package com.rioikeda.newslisten.core

/**
 * API 通信で発生しうる失敗の意味。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Networking/APIClient.swift:19-40（APIError）のミラー。
 * すべて [ApiException] の直接の子（兄弟）で、HTTP status を意味へ写すのは adapter だけ
 * （呼び出し側は status の数値でなく型で意味を選ぶ）。
 */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * 保存済みトークンでの認証が失効した（HTTP 401）。android-slice-auth-01（認証失効の型分離と検知）。
     *
     * 一時障害（[NetworkError]・5xx の [ServerError]・[DecodingError]）とは型で区別する
     * （android-contract-12。一時障害ではトークンを破棄しない）。body は読まない
     * （写像する時点で判定に必要な情報がないため）。
     */
    class Unauthorized : ApiException("認証が失効しました")

    /** 生成上限など 429 Too Many Requests（Retry-After 秒。iOS APIError.rateLimited 相当）。 */
    class RateLimited(val retryAfterSeconds: Int?) : ApiException("リクエストが多すぎます。しばらくしてからお試しください。")

    /** 入力が受け付けられなかった（HTTP 400）。 */
    class InvalidInput(val bodyMessage: String? = null) : ApiException("HTTP Error 400")

    /** 入力が検証で落ちた（HTTP 422）。400 とは型で区別する。 */
    class UnprocessableInput(val bodyMessage: String? = null) : ApiException("HTTP Error 422")

    /** 対象が見つからなかった（HTTP 404）。 */
    class NotFound(val bodyMessage: String? = null) : ApiException("HTTP Error 404")

    /** 状態が競合した（HTTP 409）。 */
    class Conflict(val bodyMessage: String? = null) : ApiException("HTTP Error 409")

    /** サーバーの障害（HTTP 5xx）。status は message にだけ入れ、property として公開しない。 */
    class ServerError(code: Int, val bodyMessage: String? = null) : ApiException("HTTP Error $code")

    /** 上のどれにも当たらない status（403 など）の fallback。 */
    class HttpError(val code: Int, val bodyMessage: String? = null) : ApiException("HTTP Error $code")

    /** レスポンスボディの JSON デコードに失敗した。 */
    class DecodingError(override val cause: Throwable) : ApiException("Decoding error: ${cause.message}", cause)

    /** ネットワーク層（接続不可・タイムアウト等、`IOException` ラップ）のエラー。 */
    class NetworkError(override val cause: Throwable) : ApiException("Network error: ${cause.message}", cause)
}
