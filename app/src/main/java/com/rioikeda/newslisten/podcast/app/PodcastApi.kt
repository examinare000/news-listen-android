package com.rioikeda.newslisten.podcast.app

import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.podcast.domain.ServerPosition
import java.time.Instant

/**
 * 再生 use case が使う狭い port（android-contract-18）。`ApiClient` の全 44 操作のうち、再生に必要な
 * 5 操作だけを切り出す。`learning.app.LearningApi`・`learning.app.VocabularyTestApi` と同方式。
 *
 * 正本: docs/design/modules/android/2026-09-16-implementation-spec-playback-auth.md §2・§4 android-contract-18。
 */
interface PodcastApi {
    /** Podcast 一覧を取得する。 */
    suspend fun fetchPodcasts(): List<Episode>

    /** 指定 ID の Podcast を取得する（署名付き audio_url の再取得にも使う）。 */
    suspend fun fetchPodcast(id: String): Episode

    /** 指定 Podcast の再生位置を [recordedAt] の時刻つきで更新する。レスポンスはサーバーが持つ位置と時刻。 */
    suspend fun updatePlaybackPosition(id: String, positionSeconds: Double, recordedAt: Instant): ServerPosition

    /** 完聴を best-effort で記録する呼び出し元のための API。 */
    suspend fun markCompleted(id: String)

    /**
     * 指定 URL から音声データをダウンロードする（署名付き外部 URL、認証ヘッダ非付与）。
     *
     * 実ファイル保存への接続はフェーズ8 で行うため、フェーズ2 はバイト列取得までの実装。
     */
    suspend fun downloadAudio(url: String): ByteArray
}
