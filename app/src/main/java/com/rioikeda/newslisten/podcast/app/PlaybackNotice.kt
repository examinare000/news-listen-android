package com.rioikeda.newslisten.podcast.app

import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.learning.app.LearningNotice

/**
 * 画面に出す再生の知らせの種類（android-dec-69）。種類だけを持ち、文言は presentation の `playbackNoticeMessage` が選ぶ（android-dec-144）。
 * 例外の message や生成の失敗の識別子の生の値は持たない。application は種類を選ぶだけで、文言を書かない。
 */
sealed interface PlaybackNotice {
    data object Offline : PlaybackNotice

    data object CacheRemoved : PlaybackNotice

    data object StillGenerating : PlaybackNotice

    data class GenerationFailed(val reason: GenerationFailureReason) : PlaybackNotice

    /** 取得の失敗のうち通信の失敗（android-dec-08）。 */
    data object FetchOffline : PlaybackNotice

    /** 取得の失敗のうち通信の失敗以外（android-dec-06）。 */
    data object FetchFailed : PlaybackNotice

    data object PlayerFailed : PlaybackNotice

    data object SaveFailed : PlaybackNotice

    data object RemoveFailed : PlaybackNotice

    /** 学習の操作の知らせ（android-dec-100）。文言は presentation の `learningNoticeMessage` が選ぶ。 */
    data class Learning(val notice: LearningNotice) : PlaybackNotice

    companion object {
        /**
         * 再生できない [Episode] の知らせ。再生できるなら `null`。
         * 生成の失敗の識別子（ADR-108 の値域）はここで一度だけ理由に写し、生の文字列は残さない。
         * `quota_exhausted` 以外（`generation_failed`・`null`・空・未知の値）は fail-closed で [GenerationFailureReason.GENERATION_FAILED]。
         */
        fun unplayable(episode: Episode): PlaybackNotice? =
            when (episode) {
                is Episode.Playable -> null
                is Episode.Generating -> StillGenerating
                is Episode.Failed -> GenerationFailed(
                    when (episode.errorMessage) {
                        "quota_exhausted" -> GenerationFailureReason.QUOTA_EXHAUSTED
                        else -> GenerationFailureReason.GENERATION_FAILED
                    },
                )
            }
    }
}

/** 生成の失敗の理由（閉じた集合）。 */
enum class GenerationFailureReason {
    GENERATION_FAILED,
    QUOTA_EXHAUSTED,
}
