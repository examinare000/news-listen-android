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

    /** デバイストークン（FCM）を登録する（フェーズ9）。 */
    data object RegisterDeviceToken : ApiEndpoint("/notifications/device-tokens", "POST")

    /**
     * デバイストークン（FCM）を解除する（フェーズ9）。
     *
     * token はクエリパラメータで渡す（backend/api/routers/notifications.py の
     * unregister_device_token と同じ規約。DismissArticle 等とは異なりパス埋め込みではない）。
     */
    data object UnregisterDeviceToken : ApiEndpoint("/notifications/device-tokens", "DELETE")

    /** 登録済み RSS 配信元一覧の取得（フェーズ10 P10）。 */
    data object FetchSources : ApiEndpoint("/settings/sources", "GET")

    /** RSS 配信元の追加（フェーズ10 P10）。 */
    data object CreateSource : ApiEndpoint("/settings/sources", "POST")

    /**
     * 既存 RSS 配信元の名称・URL の更新（フェーズ10 P10、issue #66）。
     *
     * 認可: backend は admin ロール限定（require_admin）。一般ユーザーは 403。
     */
    data object UpdateSource : ApiEndpoint("/settings/sources", "PUT")

    /**
     * 指定 URL の RSS 配信元の削除（フェーズ10 P10）。
     *
     * url は UnregisterDeviceToken と同じくクエリパラメータで渡す（backend
     * api/routers/settings.py の remove_source と同じ規約。パス埋め込みではない）。
     */
    data object DeleteSource : ApiEndpoint("/settings/sources", "DELETE")

    /** システム提供のおすすめサイト一覧の取得（フェーズ10 P10）。 */
    data object FeaturedSources : ApiEndpoint("/settings/featured-sources", "GET")

    /** ユーザー設定選択（難易度・再生速度）を更新する（フェーズ10 P10）。 */
    data object UpdatePreferences : ApiEndpoint("/settings/preferences", "PUT")

    /** Podcast 生成の本日残回数を取得する（フェーズ10 P10、issue #164・ADR-061）。 */
    data object GenerationQuota : ApiEndpoint("/users/me/generation-quota", "GET")

    /** 聴取ストリーク（連続聴取日数）を取得する（フェーズ10 P10、issue #165）。 */
    data object ListeningStreak : ApiEndpoint("/users/me/listening-streak", "GET")
}
