package com.rioikeda.newslisten.network

/**
 * バックエンド API のエンドポイント定義（パスと HTTP メソッド）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Networking/APIEndpoint.swift のミラー。
 * フェーズ2 のスコープ（認証・フィード・Star/Dismiss・Podcast・設定）の10エンドポイントのみを対象とする。
 */
sealed class ApiEndpoint(val path: String, val method: String) {
    /** ログイン（セッション発行）。 */
    data object Login : ApiEndpoint("/auth/login", "POST")

    /** ログアウト（セッション破棄）。 */
    data object Logout : ApiEndpoint("/auth/logout", "POST")

    /** ログイン中ユーザー情報の取得。 */
    data object Me : ApiEndpoint("/auth/me", "GET")

    /** フィード記事一覧の取得（filter クエリは呼び出し側で付与する）。 */
    data object Feed : ApiEndpoint("/feed", "GET")

    /** 記事を Star する。 */
    data class StarArticle(val id: String) : ApiEndpoint("/articles/$id/star", "POST")

    /** 記事を Dismiss する。 */
    data class DismissArticle(val id: String) : ApiEndpoint("/articles/$id/dismiss", "POST")

    /** Podcast 一覧の取得。 */
    data object Podcasts : ApiEndpoint("/podcasts", "GET")

    /** 指定 ID の Podcast を取得（署名付き audio_url 再取得用）。 */
    data class Podcast(val id: String) : ApiEndpoint("/podcasts/$id", "GET")

    /** 指定 ID の Podcast の再生位置を更新。 */
    data class UpdatePlaybackPosition(val id: String) : ApiEndpoint("/podcasts/$id/position", "PATCH")

    /** ユーザー設定選択（難易度・再生速度）を取得。 */
    data object Preferences : ApiEndpoint("/settings/preferences", "GET")
}
