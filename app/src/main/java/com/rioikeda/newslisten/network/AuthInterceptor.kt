package com.rioikeda.newslisten.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * バックエンド API 呼び出しに `X-API-Key` / `Authorization: Bearer` を付与する Interceptor。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Networking/APIClient.swift:415-423（buildRequest）のミラー。
 * iOS は downloadAudio を buildRequest の外側で組み立てる構造分離によってヘッダ非付与を実現するが、
 * Android は単一の [okhttp3.OkHttpClient] にグローバル Interceptor を挟む構成を採るため、
 * リクエスト URL の host が [apiBaseUrl] の host と一致する場合のみヘッダを付与する
 * 実行時判定に置き換える（署名付き外部 URL への誤付与を防ぐ）。
 *
 * @param apiBaseUrl バックエンド API のベース URL。この host と一致するリクエストのみ対象。
 * @param apiKey `X-API-Key` ヘッダに付与する API キー（ゲートウェイ認証）。
 * @param tokenProvider セッショントークンを都度取得する関数。`null` を返す間は未ログイン扱いで
 *   `Authorization` ヘッダを付与しない（SessionStore 実装はフェーズ3）。
 */
class AuthInterceptor(
    private val apiBaseUrl: HttpUrl,
    private val apiKey: String,
    private val tokenProvider: () -> String?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        // scheme・host・port の三点一致で判定する。単なる host 比較では
        // 異なるスキーム・ポートの URL にヘッダが付与されてしまう。
        if (original.url.scheme != apiBaseUrl.scheme ||
            original.url.host != apiBaseUrl.host ||
            original.url.port != apiBaseUrl.port
        ) {
            return chain.proceed(original)
        }
        val builder = original.newBuilder().header("X-API-Key", apiKey)
        tokenProvider()?.let { token -> builder.header("Authorization", "Bearer $token") }
        return chain.proceed(builder.build())
    }
}
