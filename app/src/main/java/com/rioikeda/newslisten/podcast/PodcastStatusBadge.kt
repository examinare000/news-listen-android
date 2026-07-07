package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.model.PodcastResponse

/**
 * Podcast 生成ステータスを UI 表示用の粒度へ変換した値。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Podcast/PodcastRowView.swift:104-121（statusBadge）。
 * `completed` はバッジ非表示、`processing` は「生成中」、`failed`/`partial_failed` は
 * 「失敗」に統合する（iOS 側も同じ2ステータスを1つの表示に丸めている）。
 */
sealed class PodcastStatusBadge {
    /** バッジなし（completed およびその他未知の状態）。 */
    object None : PodcastStatusBadge()

    /** 生成中。 */
    object Processing : PodcastStatusBadge()

    /** 生成失敗（failed / partial_failed 統合）。エラーメッセージは無い場合がある。 */
    data class Failed(val message: String?) : PodcastStatusBadge()

    companion object {
        /** [PodcastResponse.status] から表示用バッジを導出する。 */
        fun from(podcast: PodcastResponse): PodcastStatusBadge = when (podcast.status) {
            "processing" -> Processing
            "failed", "partial_failed" -> Failed(podcast.errorMessage)
            else -> None
        }
    }
}
