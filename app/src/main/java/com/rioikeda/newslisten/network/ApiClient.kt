package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.model.ActionResponse
import com.rioikeda.newslisten.model.FeaturedSitesResponse
import com.rioikeda.newslisten.model.FeedResponse
import com.rioikeda.newslisten.model.GenerationQuotaResponse
import com.rioikeda.newslisten.model.ListeningStreakResponse
import com.rioikeda.newslisten.model.LoginResponse
import com.rioikeda.newslisten.model.PodcastListResponse
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.model.PreferencesResponse
import com.rioikeda.newslisten.model.RssSourcesResponse
import com.rioikeda.newslisten.model.StarRequest
import com.rioikeda.newslisten.model.UserResponse

/**
 * バックエンド API への通信を担うクライアント（フェーズ2 スコープ）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Networking/APIClient.swift のミラー。
 */
interface ApiClient {
    /** ログインしてセッショントークンとユーザー情報を取得する。 */
    suspend fun login(username: String, password: String): LoginResponse

    /** ログアウトしてサーバ側セッションを破棄する。 */
    suspend fun logout()

    /** ログイン中ユーザー情報を取得する。 */
    suspend fun me(): UserResponse

    /** フィードの記事一覧を取得する。 @param filter "all" | "unread" */
    suspend fun fetchFeed(filter: String): FeedResponse

    /** 指定 ID の記事を Star する。 */
    suspend fun starArticle(id: String, request: StarRequest): ActionResponse

    /** 指定 ID の記事を Dismiss する。 */
    suspend fun dismissArticle(id: String): ActionResponse

    /** Podcast 一覧を取得する。 */
    suspend fun fetchPodcasts(): PodcastListResponse

    /** 指定 ID の Podcast を取得する（署名付き audio_url の再取得にも使う）。 */
    suspend fun fetchPodcast(id: String): PodcastResponse

    /** 指定 Podcast の再生位置を更新する。レスポンスは更新後の Podcast 全体。 */
    suspend fun updatePlaybackPosition(id: String, positionSeconds: Double): PodcastResponse

    /** ユーザー設定選択（難易度・再生速度）を取得する。 */
    suspend fun fetchPreferences(): PreferencesResponse

    /**
     * 指定 URL から音声データをダウンロードする（署名付き外部 URL、認証ヘッダ非付与）。
     *
     * 実ファイル保存への接続はフェーズ8 で行うため、フェーズ2 はバイト列取得までの実装。
     */
    suspend fun downloadAudio(url: String): ByteArray

    /**
     * デバイストークン（FCM）を登録する（冪等: 同一 token は upsert。フェーズ9）。
     *
     * @param platform backend は platform 別にトークン形式を検証し保存先を分岐する
     * （android は FCM 形式・fcmDeviceTokens コレクション。正本: backend api/routers/notifications.py）。
     */
    suspend fun registerDeviceToken(token: String, platform: String = "android")

    /** デバイストークン（FCM）を解除する（冪等: 不在でも成功扱い。フェーズ9）。 */
    suspend fun unregisterDeviceToken(token: String, platform: String = "android")

    /** 登録済みの RSS 配信元一覧を取得する（フェーズ10 P10）。 */
    suspend fun fetchSources(): RssSourcesResponse

    /** RSS 配信元を追加し、更新後の一覧を返す（フェーズ10 P10）。 */
    suspend fun createSource(name: String, url: String): RssSourcesResponse

    /**
     * 既存 RSS 配信元の名称・URL を更新し、更新後の一覧を返す（フェーズ10 P10、issue #66）。
     *
     * @param oldUrl 更新対象を特定する既存の RSS フィード URL。
     */
    suspend fun updateSource(oldUrl: String, name: String, url: String): RssSourcesResponse

    /** 指定 URL の RSS 配信元を削除し、更新後の一覧を返す（フェーズ10 P10）。 */
    suspend fun deleteSource(url: String): RssSourcesResponse

    /** システム提供のおすすめサイト一覧を取得する（フェーズ10 P10）。 */
    suspend fun fetchFeaturedSites(): FeaturedSitesResponse

    /**
     * ユーザー設定選択（難易度・再生速度）を更新する（フェーズ10 P10）。指定した項目のみ送る。
     *
     * digest 系フィールドは今回の UI スコープ外のため、このシグネチャでは送信しない
     * （iOS APIClient.swift:151 の2引数シグネチャに準拠）。
     */
    suspend fun updatePreferences(defaultDifficulty: String?, defaultPlaybackSpeed: Double?): PreferencesResponse

    /** Podcast 生成の本日残回数を取得する（フェーズ10 P10、issue #164・ADR-061）。 */
    suspend fun fetchGenerationQuota(): GenerationQuotaResponse

    /** 聴取ストリーク（連続聴取日数）を取得する（フェーズ10 P10、issue #165）。 */
    suspend fun fetchListeningStreak(): ListeningStreakResponse
}
