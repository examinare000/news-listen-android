package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.model.ActionResponse
import com.rioikeda.newslisten.model.FeedResponse
import com.rioikeda.newslisten.model.LoginResponse
import com.rioikeda.newslisten.model.PodcastListResponse
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.model.PreferencesResponse
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
}
