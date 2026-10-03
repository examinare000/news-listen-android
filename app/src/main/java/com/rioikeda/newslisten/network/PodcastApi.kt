package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.model.PodcastListResponse
import com.rioikeda.newslisten.model.PodcastResponse

/**
 * 再生 use case が使う狭い port（CI-T16）。`ApiClient` の全 44 操作のうち、再生に必要な
 * 5 操作だけを切り出す。`LearningApi`/`VocabularyTestApi` と同方式。
 *
 * 正本: docs/design/modules/android/2026-09-16-implementation-spec-playback-auth.md §2・§4 CI-T16。
 */
interface PodcastApi {
    /** Podcast 一覧を取得する。 */
    suspend fun fetchPodcasts(): PodcastListResponse

    /** 指定 ID の Podcast を取得する（署名付き audio_url の再取得にも使う）。 */
    suspend fun fetchPodcast(id: String): PodcastResponse

    /** 指定 Podcast の再生位置を更新する。レスポンスは更新後の Podcast 全体。 */
    suspend fun updatePlaybackPosition(id: String, positionSeconds: Double): PodcastResponse

    /** 完聴を best-effort で記録する呼び出し元のための API。 */
    suspend fun markCompleted(id: String)

    /**
     * 指定 URL から音声データをダウンロードする（署名付き外部 URL、認証ヘッダ非付与）。
     *
     * 実ファイル保存への接続はフェーズ8 で行うため、フェーズ2 はバイト列取得までの実装。
     */
    suspend fun downloadAudio(url: String): ByteArray
}
