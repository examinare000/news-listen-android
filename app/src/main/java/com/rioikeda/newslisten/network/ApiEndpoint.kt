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

    // --- フェーズ11 P11 Task1: アカウント管理 ---

    /** プロフィール（表示名）を更新する。 */
    data object UpdateProfile : ApiEndpoint("/auth/me", "PATCH")

    /** パスワードを変更する。現パスワード誤りは 400、新パスワード強度不足は 422。 */
    data object ChangePassword : ApiEndpoint("/auth/password", "POST")

    /** ログイン中セッション一覧を取得する。 */
    data object ListSessions : ApiEndpoint("/auth/sessions", "GET")

    /** 指定 ID のセッションを失効させる（404 は冪等成功扱い。iOS SessionsViewModel:57 準拠）。 */
    data class RevokeSession(val id: String) : ApiEndpoint("/auth/sessions/$id", "DELETE")

    /** 自セッション以外の全セッションを失効させる。 */
    data object RevokeOtherSessions : ApiEndpoint("/auth/sessions/revoke-others", "POST")

    /**
     * クラッシュ/クライアントエラーを報告する（フェーズ12・issue #140）。
     *
     * 認証セッション不要（backend api/routers/client_errors.py: X-API-Key のみで受理）。
     */
    data object ReportClientError : ApiEndpoint("/client-errors", "POST")

    // --- フェーズ13: 初回オンボーディング（issue #140 P13） ---

    /** 初回オンボーディングの完了状態を取得する。 */
    data object OnboardingStatus : ApiEndpoint("/settings/onboarding", "GET")

    /** 初回オンボーディング完了を記録する。 */
    data object CompleteOnboarding : ApiEndpoint("/settings/onboarding/complete", "POST")

    // --- フェーズ17: Passkey（WebAuthn）issue #140 P17 ---

    /** パスキー登録オプションの取得（ログイン必須）。 */
    data object PasskeyRegisterOptions : ApiEndpoint("/auth/passkey/register/options", "POST")

    /** パスキー登録の検証（ログイン必須）。 */
    data object PasskeyRegisterVerify : ApiEndpoint("/auth/passkey/register/verify", "POST")

    /** パスキー認証オプションの取得（認証不要・discoverable credential フロー）。 */
    data object PasskeyLoginOptions : ApiEndpoint("/auth/passkey/login/options", "POST")

    /** パスキー認証の検証（認証不要）。 */
    data object PasskeyLoginVerify : ApiEndpoint("/auth/passkey/login/verify", "POST")

    /** ログインユーザーのパスキー一覧の取得。 */
    data object PasskeyCredentials : ApiEndpoint("/auth/passkey/credentials", "GET")

    /** 指定 ID のパスキーを削除する。 */
    data class DeletePasskeyCredential(val id: String) : ApiEndpoint("/auth/passkey/credentials/$id", "DELETE")
}
